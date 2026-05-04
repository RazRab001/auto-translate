package cz.mendelu.auto.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimální zabezpečení API endpointů.
 *
 * <p>Implementuje HTTP Basic autentizaci se dvěma rolemi:
 * <ul>
 *   <li><b>{@code MAXIMO}</b> — smí volat všechny překladové endpointy
 *       ({@code POST /api/v1/translate}, {@code GET /jobs/*},
 *       {@code POST /api/v1/translate/sync}). Maximo MIF používá
 *       tyto credentials při HTTP requestech.</li>
 *   <li><b>{@code ADMIN}</b> — má rovněž oprávnění k~administrativním
 *       operacím ({@code POST /api/v1/cache/clear}, Swagger UI,
 *       OpenAPI specifikace).</li>
 * </ul>
 *
 * <p><b>Veřejně přístupné endpoints (bez autentizace):</b>
 * pouze {@code GET /api/v1/health} (Kubernetes liveness probe).
 *
 * <p><b>Důležité pro produkci:</b> InMemoryUserDetailsManager je
 * pouze pro pilotní prototyp. Pro produkční nasazení je třeba:
 * <ol>
 *   <li>Externalizovat credentials do Vault / Kubernetes Secrets
 *       namísto {@code application.yml},</li>
 *   <li>Přejít na~OAuth2 / mutual TLS (mTLS) mezi Maximem a~middlewarem,</li>
 *   <li>Doplnit rate limiting (Bucket4j / Resilience4j) na~všech endpointech.</li>
 * </ol>
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.security.maximo-user.username:maximo}")
    private String maximoUsername;
    @Value("${app.security.maximo-user.password:maximo-pwd-change-me}")
    private String maximoPassword;
    @Value("${app.security.admin-user.username:admin}")
    private String adminUsername;
    @Value("${app.security.admin-user.password:admin-pwd-change-me}")
    private String adminPassword;
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String jwtIssuerUri;

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Fail-safe-by-default: aplikace odmítne startovat
     * s~výchozími hesly {@code *-pwd-change-me}, pokud není explicitně
     * povolen \emph{development} profil. Tím se vyhneme fail-open scénáři,
     * kdy nasazení bez {@code SPRING_PROFILES_ACTIVE} by bylo tiše
     * považováno za~dev a~spuštěno s~výchozími hesly.
     *
     * <p>Pravidla:
     * <ul>
     *   <li>Aktivní profil obsahuje některý z~{@code dev / test / local /
     *       integration} (case-insensitive) → výchozí hesla jsou tolerována,
     *       jen INFO log.</li>
     *   <li>Jakýkoli jiný stav (žádný profil, prod, prd, production, live,
     *       release, staging, ...) + výchozí hesla + žádný JWT issuer-uri
     *       → hard fail s~{@link IllegalStateException}.</li>
     *   <li>Stejný stav + JWT issuer-uri nakonfigurován → WARN (Basic auth
     *       je fallback k~OAuth2, ale doporučujeme rotovat).</li>
     * </ul>
     */
    @PostConstruct
    public void validateProductionSecurity() {
        // Least-privilege evaluation. If ANY non-dev token
        // (prod/staging/production/live/release/...) is present alongside
        // a dev-ish token, treat the deployment as non-dev (strict). Mixed
        // profile "dev,prod" must therefore fail, not boot with defaults.
        String[] active = environment.getActiveProfiles();
        boolean anyDev = false;
        boolean anyNonDev = false;
        for (String p : active) {
            String lp = p.toLowerCase();
            if (lp.equals("dev") || lp.equals("development")
                    || lp.equals("test") || lp.equals("local")
                    || lp.equals("integration") || lp.equals("ci")) {
                anyDev = true;
            } else {
                anyNonDev = true;
            }
        }
        boolean devProfile = anyDev && !anyNonDev;
        String activeList = "[" + String.join(",", active) + "]";
        boolean usingDefaults = "maximo-pwd-change-me".equals(maximoPassword)
                             || "admin-pwd-change-me".equals(adminPassword);
        boolean noJwtIssuer = jwtIssuerUri == null || jwtIssuerUri.isBlank();

        if (devProfile) {
            if (usingDefaults) {
                log.info("[SECURITY] Dev/test profile {} with default "
                        + "passwords. These MUST be rotated for non-dev "
                        + "deployment.", activeList);
            }
            return;
        }
        // Non-dev (prod, staging, no profile, ...) — strict.
        if (usingDefaults && noJwtIssuer) {
            throw new IllegalStateException(
                    "[SECURITY] Non-dev deployment detected (active profiles="
                    + activeList + ") with default passwords AND no JWT "
                    + "issuer-uri. Application will NOT start in this "
                    + "insecure state. Either set explicit dev/test/local "
                    + "profile, OR set MAXIMO_PASSWORD/ADMIN_PASSWORD env "
                    + "vars, OR configure "
                    + "spring.security.oauth2.resourceserver.jwt.issuer-uri.");
        }
        if (usingDefaults) {
            log.warn("[SECURITY] Non-dev deployment uses default Basic auth "
                    + "passwords (active profiles={}); OAuth2 JWT is "
                    + "configured ({}). Recommend rotating Basic auth "
                    + "fallback credentials before production go-live.",
                    activeList, jwtIssuerUri);
        }
        if (noJwtIssuer) {
            log.warn("[SECURITY] Non-dev deployment without OAuth2 JWT "
                    + "issuer-uri (active profiles={}); relying on Basic "
                    + "auth only. Recommend configuring JWT for "
                    + "production.", activeList);
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(PasswordEncoder encoder) {
        UserDetails maximo = User.builder()
                .username(maximoUsername)
                .password(encoder.encode(maximoPassword))
                .roles("MAXIMO")
                .build();
        UserDetails admin = User.builder()
                .username(adminUsername)
                .password(encoder.encode(adminPassword))
                .roles("MAXIMO", "ADMIN")
                .build();
        log.info("Loaded {} security users (roles: MAXIMO, ADMIN)", 2);
        return new InMemoryUserDetailsManager(maximo, admin);
    }

    /**
     * Hlavní filter chain. Podporuje DVĚ autentizační metody současně:
     * <ol>
     *   <li><b>HTTP Basic</b> --- pro vývojový a~minimal deployment scénář
     *       (in-memory user store výše).</li>
     *   <li><b>OAuth2 JWT Bearer</b> --- aktivuje se automaticky pokud je
     *       nastavena property
     *       {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
     *       (např. Azure AD, Keycloak, Okta). V~produkci je toto preferovaná
     *       metoda (rotace tokenů, žádné sdílené passwords).</li>
     * </ol>
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<
                    org.springframework.security.oauth2.jwt.JwtDecoder> jwtDecoderProvider
    ) throws Exception {
        http
            // CSRF off pro REST API (stateless)
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/health").permitAll()
                // Web konzole — statický HTML/JS dashboard. Sám konzolový HTML
                // je volně přístupný (jen UI shell); REST volání z prohlížeče
                // potřebují Basic auth (uživatel zadá v UI form).
                .requestMatchers("/", "/console", "/console.html",
                                 "/favicon.ico", "/static/**",
                                 "/css/**", "/js/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                 "/swagger-ui.html").hasRole("ADMIN")
                .requestMatchers("/api/v1/cache/clear").hasRole("ADMIN")
                // Catalog endpoints: GET (read) povoleno MAXIMO+ADMIN,
                // POST sync vyžaduje ADMIN (zápis = potenciálně velké API náklady)
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                                 "/api/v1/catalogs/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/catalogs/**").hasAnyRole("MAXIMO", "ADMIN")
                // SQL proxy: read-only SELECT pro klientské aplikace (drop-in
                // pre existující SQL klienty)
                .requestMatchers("/api/v1/sql").hasAnyRole("MAXIMO", "ADMIN")
                // Mode 2 — simple translate (text + document upload)
                .requestMatchers("/api/v1/translate/document",
                                 "/api/v1/translate/**",
                                 "/api/v1/jobs/**").hasAnyRole("MAXIMO", "ADMIN")
                .anyRequest().authenticated())
            // AuthN/AccessDenied bypass @ControllerAdvice;
            // emit the SAME {error,message,correlationId} envelope as
            // GlobalExceptionHandler so Maximo client gets one consistent
            // schema across security and application errors.
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, res, ex) -> writeEnvelope(
                        res, 401, "unauthorized",
                        "Authentication required."))
                .accessDeniedHandler((req, res, ex) -> writeEnvelope(
                        res, 403, "forbidden",
                        "Insufficient role for this endpoint.")))
            .httpBasic(Customizer.withDefaults());

        // Pokud je nakonfigurován JWT issuer (produkce), aktivujeme OAuth2
        // Resource Server vedle Basic auth.
        if (jwtDecoderProvider.getIfAvailable() != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                .jwt(Customizer.withDefaults()));
            log.info("OAuth2 Resource Server (JWT) is ENABLED alongside Basic auth");
        } else {
            log.info("OAuth2 Resource Server is INACTIVE "
                    + "(set spring.security.oauth2.resourceserver.jwt.issuer-uri "
                    + "to activate in production)");
        }
        return http.build();
    }

    /**
     * Vytvoří jednotnou JSON odpověď ve stejném tvaru jako
     * {@code GlobalExceptionHandler}.
     */
    private static void writeEnvelope(
            jakarta.servlet.http.HttpServletResponse res,
            int status, String error, String message
    ) throws java.io.IOException {
        String cid = "err-" + java.util.UUID.randomUUID();
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        // Use Jackson via simple manual JSON to avoid pulling extra dep here.
        String body = "{\"error\":\"" + error + "\","
                + "\"message\":\"" + message + "\","
                + "\"correlationId\":\"" + cid + "\"}";
        res.getWriter().write(body);
    }
}
