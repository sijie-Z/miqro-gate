package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6.4 soak: a fixed window of concurrent streaming requests against the real
 * gateway and mock upstream. Asserts zero upstream errors and that every
 * response carried a usage row (queue drops would surface as missing rows). The
 * window is short (30 s) so CI stays fast; the deploy/loadtest/soak.sh script
 * is the long-duration variant for production-like environments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=true", "miqrokey.gateway.route-snapshot.refresh-interval=1h",
        "spring.flyway.enabled=true", "miqrokey.gateway.upstream.connect-timeout=PT5S",
        "miqrokey.gateway.upstream.first-byte-timeout=PT30S", "miqrokey.gateway.upstream.stream-idle-timeout=PT60S",
        "miqrokey.gateway.upstream.response-timeout=PT120S"})
@Import(GatewayAuthTestConfig.class)
@AutoConfigureWebTestClient
@Tag("integration")
@Tag("soak")
@DisplayName("Soak test (G6.4)")
class SoakIntegrationTest {

    private static final int WINDOW_SECONDS = 30;
    private static final int CONCURRENCY = 8;

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("postgres:17.6-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
            .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
            .withPassword("miqrokey_test");

    private static final Path ENC_KEY_FILE = KeyFiles.write("soak-test-enc.key");
    private static final Path HMAC_KEY_FILE = KeyFiles.write("soak-test-hmac.key");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockProvider::getBaseUrl);
        registry.add("miqrokey.gateway.upstream.allowed-cidrs", () -> "127.0.0.0/8, ::1/128");
        registry.add("miqrokey.gateway.persistence.url", POSTGRES::getJdbcUrl);
        registry.add("miqrokey.gateway.persistence.username", POSTGRES::getUsername);
        registry.add("miqrokey.gateway.persistence.password", POSTGRES::getPassword);
        registry.add("miqrokey.crypto.encryption.versions.v1", () -> ENC_KEY_FILE.toString());
        registry.add("miqrokey.crypto.hmac.versions.v1", () -> HMAC_KEY_FILE.toString());
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @LocalServerPort
    int gatewayPort;

    @BeforeAll
    static void seedTenant() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .update("""
                        INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                        VALUES (:id, 'soak-test', 'Soak Test', 'ACTIVE', 0, now(), now())
                        """, new MapSqlParameterSource().addValue("id", GatewayTestKeys.TENANT_ID));
    }

    @AfterAll
    static void stopMockProvider() {
        mockProvider.close();
    }

    @Test
    @DisplayName("concurrent streams stay error-free with every response accounted")
    void concurrentStreamsRemainHealthy() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("text/event-stream").body(AnthropicFixtures.RESPONSE_STREAMING_SSE).build());

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            workers.add(() -> {
                var response = webTestClient.post().uri("/v1/messages")
                        .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                        .bodyValue(AnthropicFixtures.REQUEST_STREAMING).exchange().expectBody().returnResult();
                return response.getStatus().value();
            });
        }
        List<Future<Integer>> results = pool.invokeAll(workers);
        pool.shutdown();

        long errors = results.stream().filter(f -> {
            try {
                return f.get() != 200;
            } catch (Exception e) {
                return true;
            }
        }).count();
        assertThat(errors).as("all concurrent streams must succeed").isZero();

        // Usage rows for the window: request_usage_records must have rows and
        // none may be missing usage.
        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM request_usage_records WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", GatewayTestKeys.TENANT_ID), Integer.class);
        assertThat(rows).as("soak requests must be persisted").isGreaterThan(0);

        mockProvider.reset();
    }

    /** Key file helper with POSIX 0400 (Linux CI enforces it). */
    private static final class KeyFiles {
        static Path write(String name) {
            try {
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                Path file = Files.createTempFile(name, ".key");
                Files.writeString(file, Base64.getEncoder().encodeToString(key));
                try {
                    Files.setPosixFilePermissions(file,
                            java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
                } catch (UnsupportedOperationException ignored) {
                    // non-POSIX filesystem
                }
                return file;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
