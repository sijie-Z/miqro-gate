package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.queue.UsageEventBus;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end usage lifecycle: the gateway proxies to the mock provider while
 * persistence is enabled, and every request that reaches upstream lands in
 * {@code request_usage_records} — finalized exactly once with the correct
 * terminal status, including client cancellation and upstream outage.
 *
 * <p>
 * The fixture route snapshot ({@link GatewayAuthTestConfig}) points at the mock
 * provider, so the lifecycle rows carry the fixture identity chain
 * (tenant/user/key/product/provider/credential) without seeding the database.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=true", "miqrokey.gateway.route-snapshot.refresh-interval=1h",
        "spring.flyway.enabled=true"})
@Import(GatewayAuthTestConfig.class)
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Usage lifecycle end-to-end (gateway + PostgreSQL)")
class UsageLifecycleIntegrationTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName
                .parse("postgres:17.6-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
                .withPassword("miqrokey_test");
        POSTGRES.start();
    }

    private static final Path ENC_KEY_FILE = KeyFiles.write("lifecycle-test-enc.key");
    private static final Path HMAC_KEY_FILE = KeyFiles.write("lifecycle-test-hmac.key");

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
    private WebTestClient webTestClient;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private UsageEventBus usageEventBus;

    @LocalServerPort
    private int gatewayPort;

    /**
     * Wall-clock start of the current test; rows finalized before it are ignored.
     */
    private long sinceEpochMillis;

    @BeforeEach
    void recordTestStart() {
        sinceEpochMillis = System.currentTimeMillis();
    }

    @BeforeAll
    static void seedTenant() {
        // The Spring context (and its Flyway run) is not yet loaded at
        // beforeAll-time, so migrate manually, then seed the fixture tenant:
        // request_usage_records.tenant_id is FK-constrained (ON DELETE RESTRICT)
        // and the fixture tenant must exist for lifecycle inserts to land.
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .update("""
                        INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                        VALUES (:id, 'lifecycle-test', 'Lifecycle Test', 'ACTIVE', 0, now(), now())
                        """, new MapSqlParameterSource().addValue("id", GatewayTestKeys.TENANT_ID));
    }

    @AfterAll
    static void stopMockProvider() {
        mockProvider.close();
    }

    @AfterEach
    void resetMockProvider() {
        mockProvider.reset();
    }

    // -------------------------------------------------------------------
    // Lifecycle rows for completed requests
    // -------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("non-streaming 200 finalizes a SUCCEEDED record with parsed usage")
    void nonStreamingSuccessFinalizesSucceededRow() throws Exception {
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("application/json")
                        .header("x-request-id", "req-lifecycle-001").body(AnthropicFixtures.RESPONSE_BASIC).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("http_status", 200);
        assertThat(row).containsEntry("wire_protocol", "ANTHROPIC_MESSAGES");
        assertThat(row).containsEntry("streaming", false);
        assertThat(row).containsEntry("usage_missing", false);
        assertThat(row).containsEntry("model_id", "claude-sonnet-5-20250915");
        assertThat(row).containsEntry("client_cancelled", false);
        assertThat(row).containsEntry("partial_response", false);
        assertThat(row).containsEntry("upstream_request_id", "req-lifecycle-001");
        assertThat(row).containsEntry("tenant_id", GatewayTestKeys.TENANT_ID);
        assertThat(row).containsEntry("virtual_key_id", GatewayTestKeys.DEFAULT_KEY.keyId());
        assertThat(row).containsEntry("user_id", GatewayTestKeys.DEFAULT_KEY.userId());
        assertThat(row).containsEntry("project_id", GatewayTestKeys.DEFAULT_KEY.projectId());
        assertThat(row).containsEntry("provider_product_id", GatewayTestKeys.DEFAULT_KEY.productId());
        assertThat(row).containsEntry("provider_id", GatewayTestKeys.DEFAULT_KEY.providerId());
        assertThat(row).containsEntry("credential_id", GatewayTestKeys.DEFAULT_KEY.credentialId());
        assertThat(row).containsEntry("input_tokens", 10L);
        assertThat(row).containsEntry("output_tokens", 7L);
        assertThat(row.get("first_byte_at")).isNotNull();
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("finalized_at")).isNotNull();
        assertThat(row.get("duration_ms")).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("streaming SSE finalizes SUCCEEDED with streaming=true and parsed tokens")
    void streamingSseFinalizesWithTokens() throws Exception {
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                        .body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_STREAMING).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("streaming", true);
        assertThat(row).containsEntry("usage_missing", false);
        // Anthropic SSE carries input/output tokens (merged across message_start
        // and message_delta) but no OpenAI-style total_tokens field.
        assertThat(row.get("input_tokens")).isNotNull();
        assertThat(row.get("output_tokens")).isNotNull();
        assertThat(row.get("total_tokens")).isNull();
        assertThat(row.get("finalized_at")).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("a 200 without usage fields is explicitly flagged usage_missing")
    void successWithoutUsageIsMarkedUsageMissing() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body("{\"id\":\"msg_no_usage\"}").build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("usage_missing", true);
        assertThat(row.get("input_tokens")).isNull();
    }

    @Test
    @Order(4)
    @DisplayName("a non-2xx upstream response finalizes UPSTREAM_REJECTED")
    void upstreamRejectionFinalizesRejectedRow() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(429)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_ERROR_400).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isEqualTo(429).expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "UPSTREAM_REJECTED");
        assertThat(row).containsEntry("http_status", 429);
        assertThat(row.get("finalized_at")).isNotNull();
    }

    @Test
    @Order(5)
    @DisplayName("a client disconnect mid-stream finalizes CLIENT_CANCELLED")
    void clientCancellationFinalizesCancelledRow() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("text/event-stream").body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                .chunkDelay(Duration.ofMillis(50)).build());

        Flux<org.springframework.core.io.buffer.DataBuffer> responseBody = WebClient
                .create("http://localhost:" + gatewayPort).post().uri("/v1/messages")
                .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                .bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                .exchangeToFlux(response -> response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class));
        StepVerifier.create(responseBody).consumeNextWith(DataBufferUtils::release).thenCancel()
                .verify(Duration.ofSeconds(15));

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "CLIENT_CANCELLED");
        assertThat(row).containsEntry("client_cancelled", true);
        assertThat(row).containsEntry("streaming", true);
        assertThat(row.get("finalized_at")).isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("an unreachable upstream finalizes UPSTREAM_UNAVAILABLE (502 to the client)")
    void upstreamOutageFinalizesUnavailableRow() throws Exception {
        mockProvider.close(); // port stops listening -> connection refused

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isEqualTo(502).expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "UPSTREAM_UNAVAILABLE");
        assertThat(row.get("http_status")).isNull();
        assertThat(row.get("finalized_at")).isNotNull();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Flushes the bus and polls for the latest lifecycle row of the fixture key
     * finalized at or after the current test's start — a test never picks up an
     * earlier test's row while its own is still being written.
     */
    private Map<String, Object> awaitLatestLifecycleRow() throws Exception {
        usageEventBus.flush();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            usageEventBus.flush();
            var rows = jdbc.queryForList("""
                    SELECT * FROM request_usage_records
                    WHERE tenant_id = :tenantId AND virtual_key_id = :keyId AND finalized_at >= :since
                    ORDER BY started_at DESC LIMIT 1
                    """,
                    new MapSqlParameterSource().addValue("tenantId", GatewayTestKeys.TENANT_ID)
                            .addValue("keyId", GatewayTestKeys.DEFAULT_KEY.keyId())
                            .addValue("since", new java.sql.Timestamp(sinceEpochMillis)));
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No lifecycle row appeared within 15s");
    }

    /** Writes a fresh random 32-byte key file (base64) for the crypto config. */
    private static final class KeyFiles {
        private static final SecureRandom RANDOM = new SecureRandom();

        static Path write(String name) {
            try {
                byte[] key = new byte[32];
                RANDOM.nextBytes(key);
                Path file = Files.createTempFile(name, ".key");
                Files.writeString(file, java.util.Base64.getEncoder().encodeToString(key));
                try {
                    Files.setPosixFilePermissions(file,
                            java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystem: permission check is skipped anyway.
                }
                return file;
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
