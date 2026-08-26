package com.miqroera.miqrokey.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Optional monitoring profile (G6.1): with {@code monitoring} active the
 * Prometheus scrape endpoint is exposed and carries the low-cardinality
 * data-plane counter. The default profile keeps the endpoint closed (asserted
 * in {@link GatewayApplicationSmokeTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("monitoring")
@Tag("integration")
@DisplayName("Gateway monitoring profile (Prometheus)")
class GatewayMonitoringProfileTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
            .withPassword("miqrokey_test");
    static final Path ENC_KEY_FILE;
    static final Path HMAC_KEY_FILE;

    static {
        POSTGRES.start();
        try {
            ENC_KEY_FILE = keyFile("enc-key");
            HMAC_KEY_FILE = keyFile("hmac-key");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Path keyFile(String name) throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        Path file = Files.createTempFile(name, ".key");
        Files.writeString(file, java.util.Base64.getEncoder().encodeToString(key));
        try {
            // Linux CI enforces 0400 on key material; no-op on Windows.
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        return file;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.persistence.url", POSTGRES::getJdbcUrl);
        registry.add("miqrokey.gateway.persistence.username", POSTGRES::getUsername);
        registry.add("miqrokey.gateway.persistence.password", POSTGRES::getPassword);
        registry.add("miqrokey.crypto.encryption.versions.v1", () -> ENC_KEY_FILE.toString());
        registry.add("miqrokey.crypto.hmac.versions.v1", () -> HMAC_KEY_FILE.toString());
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    @DisplayName("prometheus endpoint is served with the request counter")
    void prometheusEndpointServesMetrics() {
        webTestClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class)
                .consumeWith(result -> {
                    String body = result.getResponseBody();
                    org.assertj.core.api.Assertions.assertThat(body).contains("miqrokey_gateway_requests_total");
                    org.assertj.core.api.Assertions.assertThat(body).contains("jvm_memory_used_bytes");
                });
    }

    @Test
    @DisplayName("a gateway request increments the 4xx counter")
    void requestCountsStatusClass() {
        webTestClient.get().uri("/v1/does-not-exist").exchange().expectStatus().isNotFound();

        webTestClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class)
                .consumeWith(result -> {
                    String body = result.getResponseBody();
                    org.assertj.core.api.Assertions.assertThat(body).contains("status_class=\"client_error\"")
                            .contains(" 1.0");
                });
    }
}
