package cz.mendelu.auto;

import cz.mendelu.auto.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * SecurityConfig.validateProductionSecurity() is fail-safe-by-default.
 * <ul>
 *   <li>Without dev profile + default passwords + no JWT issuer → IllegalStateException</li>
 *   <li>Empty active profiles list (typical "forgot SPRING_PROFILES_ACTIVE")
 *       is treated as non-dev (fail-safe), not as dev (no fail-open hole).</li>
 *   <li>"prod", "production", "staging", "live", any unknown profile + defaults
 *       → IllegalStateException.</li>
 *   <li>Explicit dev/test/local profile + defaults → no throw (only INFO log).</li>
 * </ul>
 */
class SecurityConfigValidationTest {

    private SecurityConfig newSecurityConfig(Environment env, String maximoPwd,
                                             String adminPwd, String jwtIssuer) {
        SecurityConfig sc = new SecurityConfig(env);
        ReflectionTestUtils.setField(sc, "maximoPassword", maximoPwd);
        ReflectionTestUtils.setField(sc, "adminPassword", adminPwd);
        ReflectionTestUtils.setField(sc, "jwtIssuerUri", jwtIssuer);
        ReflectionTestUtils.setField(sc, "maximoUsername", "maximo");
        ReflectionTestUtils.setField(sc, "adminUsername", "admin");
        return sc;
    }

    @Test
    void emptyProfileWithDefaultsAndNoJwtFailsFast() {
        MockEnvironment env = new MockEnvironment(); // no active profile
        SecurityConfig sc = newSecurityConfig(env,
                "maximo-pwd-change-me", "admin-pwd-change-me", "");

        assertThatThrownBy(sc::validateProductionSecurity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Non-dev deployment")
                .hasMessageContaining("default passwords");
    }

    @Test
    void prodProfileWithDefaultsAndNoJwtFailsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        SecurityConfig sc = newSecurityConfig(env,
                "maximo-pwd-change-me", "admin-pwd-change-me", "");

        assertThatThrownBy(sc::validateProductionSecurity)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stagingProfileWithDefaultsAndNoJwtFailsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");
        SecurityConfig sc = newSecurityConfig(env,
                "maximo-pwd-change-me", "admin-pwd-change-me", "");

        assertThatThrownBy(sc::validateProductionSecurity)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devProfileWithDefaultsIsAllowed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        SecurityConfig sc = newSecurityConfig(env,
                "maximo-pwd-change-me", "admin-pwd-change-me", "");

        assertThatNoException().isThrownBy(sc::validateProductionSecurity);
    }

    @Test
    void testProfileWithDefaultsIsAllowed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        SecurityConfig sc = newSecurityConfig(env,
                "maximo-pwd-change-me", "admin-pwd-change-me", "");

        assertThatNoException().isThrownBy(sc::validateProductionSecurity);
    }

    @Test
    void prodProfileWithRotatedPasswordsIsAllowed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        SecurityConfig sc = newSecurityConfig(env,
                "rotated-strong-1", "rotated-strong-2", "");

        assertThatNoException().isThrownBy(sc::validateProductionSecurity);
    }

    @Test
    void mixedDevAndProdProfileFailsFast() {
        // Least-privilege — any non-dev token coexisting
        // with a dev token must be treated as non-dev (strict).
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "prod");
        SecurityConfig sc = newSecurityConfig(env,
                "maximo-pwd-change-me", "admin-pwd-change-me", "");

        assertThatThrownBy(sc::validateProductionSecurity)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodProfileWithDefaultsAndJwtIssuerOnlyWarns() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        SecurityConfig sc = newSecurityConfig(env,
                "maximo-pwd-change-me", "admin-pwd-change-me",
                "https://login.example.com/realms/main");

        // OAuth2 fallback configured → only WARN, no throw.
        assertThatNoException().isThrownBy(sc::validateProductionSecurity);
        assertThat(sc).isNotNull();
    }
}
