package com.miqroera.miqrokey.controlplane.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ProductionStartupValidator.validate(). Each invalid production
 * configuration is tested by directly calling validate() and asserting it
 * throws with the expected message.
 *
 * <p>
 * Context-level startup failure for production mode is verified by
 * {@link ProductionStartupValidatorContextTest} which uses
 * {@code ApplicationContextRunner} to prove the Spring container refuses to
 * start.
 * </p>
 */
@DisplayName("Production startup validator unit tests")
class ProductionStartupValidatorTest {

    private static final Environment MOCK_ENV = mock(Environment.class);
    static {
        when(MOCK_ENV.getActiveProfiles()).thenReturn(new String[0]);
    }

    @Test
    @DisplayName("valid secure HTTPS configuration passes")
    void validSecureHttpsConfigPasses() {
        AuthProperties props = productionProperties(true,
                List.of("https://example.com", "https://admin.example.com:8443"));
        assertThatCode(() -> new ProductionStartupValidator(props, MOCK_ENV).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cookieSecure=false throws")
    void cookieSecureFalseThrows() {
        AuthProperties props = productionProperties(false, List.of("https://example.com"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cookieSecure=true");
    }

    @Test
    @DisplayName("null allowlist throws")
    void nullAllowlistThrows() {
        AuthProperties props = productionProperties(true, null);
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("empty allowlist throws")
    void emptyAllowlistThrows() {
        AuthProperties props = productionProperties(true, List.of());
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("localhost-only allowlist throws")
    void localhostOnlyThrows() {
        AuthProperties props = productionProperties(true, List.of("http://localhost:5173", "http://127.0.0.1:8080"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-localhost");
    }

    @Test
    @DisplayName("HTTP non-localhost origin throws")
    void httpNonLocalhostThrows() {
        AuthProperties props = productionProperties(true,
                List.of("https://example.com", "http://insecure.example.com"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP non-localhost");
    }

    @Test
    @DisplayName("origin with path throws")
    void originWithPathThrows() {
        AuthProperties props = productionProperties(true,
                List.of("https://example.com", "https://example.com/admin/login"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("path");
    }

    @Test
    @DisplayName("origin with trailing slash throws")
    void originWithTrailingSlashThrows() {
        AuthProperties props = productionProperties(true, List.of("https://example.com", "https://example.com/"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("path");
    }

    @Test
    @DisplayName("origin with query throws")
    void originWithQueryThrows() {
        AuthProperties props = productionProperties(true,
                List.of("https://example.com", "https://example.com?foo=bar"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("query");
    }

    @Test
    @DisplayName("origin with fragment throws")
    void originWithFragmentThrows() {
        AuthProperties props = productionProperties(true,
                List.of("https://example.com", "https://example.com#section"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fragment");
    }

    @Test
    @DisplayName("origin with userinfo throws")
    void originWithUserinfoThrows() {
        AuthProperties props = productionProperties(true,
                List.of("https://example.com", "https://user:pass@example.com"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("userinfo");
    }

    @Test
    @DisplayName("invalid URI throws")
    void invalidUriThrows() {
        AuthProperties props = productionProperties(true, List.of("https://example.com", ":::not-a-uri:::"));
        assertThatThrownBy(() -> new ProductionStartupValidator(props, MOCK_ENV).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("valid URI");
    }

    @Test
    @DisplayName("localhost HTTPS + valid HTTPS accepted")
    void localhostHttpsWithValidNonLocalhostAccepted() {
        AuthProperties props = productionProperties(true, List.of("https://example.com", "https://localhost:8443"));
        assertThatCode(() -> new ProductionStartupValidator(props, MOCK_ENV).validate()).doesNotThrowAnyException();
    }

    private static AuthProperties productionProperties(boolean cookieSecure, List<String> allowlist) {
        AuthProperties props = new AuthProperties();
        props.setProduction(true);
        props.setCookieSecure(cookieSecure);
        // Explicitly set allowlist — null means no origins configured
        props.setOriginAllowlist(allowlist);
        return props;
    }
}
