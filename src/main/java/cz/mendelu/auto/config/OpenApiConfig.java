package cz.mendelu.auto.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger UI konfigurace pro produkčně použitelnou interaktivní
 * dokumentaci.
 *
 * <p>Definuje:
 * <ul>
 *   <li>Top-level metadata (title, version, contact, license)</li>
 *   <li>Server URLs (lokální + příklad produkce)</li>
 *   <li>Bezpečnostní schémata (HTTP Basic auth)</li>
 *   <li>Tag groups: <b>Mode 1 (Catalog)</b> vs <b>Mode 2 (Simple)</b> +
 *       Health/Admin pomocná skupina</li>
 *   <li>Grouped APIs &mdash; oddělené záložky pro každý mode v Swagger UI</li>
 * </ul>
 *
 * <p>Po startu aplikace je Swagger UI dostupné na
 * <a href="http://localhost:8080/swagger-ui.html">/swagger-ui.html</a>
 * a~OpenAPI spec na <code>/v3/api-docs</code>.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sidecarOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Auto-Translate Sidecar API")
                        .version("1.0.0")
                        .description("""
                                Sidecar middleware pro IBM Maximo Application Suite \
                                a~obecně relační DB s~katalogovými daty. Dva nezávislé \
                                režimy:

                                * **Mode 1 - Catalog (full DB pipeline):** přečte tabulky \
                                ze~zdrojové DB, přeloží do N jazyků, uloží do TM, \
                                vystaví přes REST i přes SQL proxy (drop-in pro \
                                existující SQL klienty).
                                * **Mode 2 — Simple (text/document):** stateless překlad \
                                jednorázového textu nebo nahraného dokumentu (PDF/TXT).

                                Bachelor's thesis project, Mendel University in Brno (2026).
                                """)
                        .contact(new Contact()
                                .name("Fedor Baskaev")
                                .email("xbaskaev@mendelu.cz"))
                        .license(new License()
                                .name("Bachelor thesis project")
                                .url("https://mendelu.cz/")))
                .servers(List.of(
                        new Server().url("http://localhost:8080")
                                .description("Local development"),
                        new Server().url("https://sidecar.example.com")
                                .description("Production (example)")))
                .components(new Components()
                        .addSecuritySchemes("basicAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")
                                        .description("HTTP Basic. Demo creds: "
                                                + "`admin:admin-pwd-change-me` "
                                                + "(role ADMIN) nebo "
                                                + "`maximo:maximo-pwd-change-me` "
                                                + "(role MAXIMO).")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .tags(List.of(
                        new Tag().name("Mode 1 - Catalog Translation API")
                                .description("Full DB-driven multi-language pipeline. "
                                        + "Read tables → translate to N languages → "
                                        + "store in ES TM → retrieve by language."),
                        new Tag().name("Mode 1 - SQL Proxy")
                                .description("Drop-in transparent layer: existing app "
                                        + "sends original SELECT, gets the same rows "
                                        + "with text columns translated to chosen lang."),
                        new Tag().name("Mode 2 - Simple translation")
                                .description("Stateless text/document translation. "
                                        + "Input: JSON text or multipart file (PDF/TXT). "
                                        + "Output: translation."),
                        new Tag().name("Translation API")
                                .description("Async/sync text translation (Mode 2)."),
                        new Tag().name("Admin / Health")
                                .description("Health check, cache management.")
                ));
    }

    /** Mode 1 group — catalog endpoints + SQL proxy. */
    @Bean
    public GroupedOpenApi mode1CatalogGroup() {
        return GroupedOpenApi.builder()
                .group("1-catalog")
                .displayName("Mode 1 - Catalog + SQL Proxy")
                .pathsToMatch("/api/v1/catalogs/**", "/api/v1/sql/**", "/api/v1/sql")
                .build();
    }

    /** Mode 2 group — text + document translation. */
    @Bean
    public GroupedOpenApi mode2SimpleGroup() {
        return GroupedOpenApi.builder()
                .group("2-simple")
                .displayName("Mode 2 — Simple translate (text + document)")
                .pathsToMatch("/api/v1/translate/**", "/api/v1/jobs/**")
                .build();
    }

    /** Cross-cutting (health, cache, etc.) */
    @Bean
    public GroupedOpenApi adminGroup() {
        return GroupedOpenApi.builder()
                .group("3-admin")
                .displayName("Admin / Health")
                .pathsToMatch("/api/v1/health", "/api/v1/cache/**")
                .build();
    }
}
