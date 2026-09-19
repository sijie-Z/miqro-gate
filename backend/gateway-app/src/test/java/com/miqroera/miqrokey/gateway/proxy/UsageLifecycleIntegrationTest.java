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
                .parse("postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
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
    @Order(0)
    @DisplayName("a context-limit rejection (413) never reaches upstream and writes no lifecycle row")
    void contextLimitRejectionWritesNoLifecycleRow() throws Exception {
        // Runs before @Order(8), which closes the mock provider for the rest of
        // the class. Control request first: the same context does open a row for
        // a request that reaches upstream, so the zero-row assertion below cannot
        // pass vacuously.
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("application/json")
                        .header("x-request-id", "req-lifecycle-000").body(AnthropicFixtures.RESPONSE_BASIC).build());
        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertThat(awaitLatestLifecycleRow()).containsEntry("request_status", "SUCCEEDED");

        mockProvider.reset();
        long mark = System.currentTimeMillis();

        // 200001 ASCII characters: over the shipped context-limit default
        // (200000) yet well inside the 256KB body buffer, so only the pre-check
        // can reject it (#553).
        webTestClient.post().uri("/v1/messages").bodyValue(anthropicBodyOfLength(200_001)).exchange().expectStatus()
                .isEqualTo(413).expectBody().returnResult().getResponseBody();

        usageEventBus.flush();
        Thread.sleep(500); // a row opened by mistake would be written asynchronously
        usageEventBus.flush();

        assertThat(countLifecycleRowsSince(mark)).isZero();
        assertThat(mockProvider.getCapturedRequests()).isEmpty();
    }

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
        // Anthropic SSE carries input/output tokens (the cumulative counters of
        // message_start/message_delta) but no OpenAI-style total_tokens field.
        assertThat(row.get("input_tokens")).isNotNull();
        assertThat(row.get("output_tokens")).isNotNull();
        assertThat(row.get("total_tokens")).isNull();
        assertThat(row.get("finalized_at")).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("streaming usage equals the provider's counters instead of summing them across events")
    void streamingUsageIsNotDoubleCountedAcrossEvents() throws Exception {
        // RESPONSE_STREAMING_SSE reports usage TWICE for the same message:
        // message_start {"input_tokens":10,"output_tokens":0} and message_delta
        // {"input_tokens":10,"output_tokens":8,...}. Anthropic usage counters are
        // cumulative within one response, so the record must carry 10/8 — summing
        // the two events would bill the same input tokens twice.
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                        .body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_STREAMING).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("input_tokens", 10L);
        assertThat(row).containsEntry("output_tokens", 8L);
    }

    @Test
    @Order(4)
    @DisplayName("prompt cache usage (cache_read / cache_creation) lands in the lifecycle row")
    void promptCacheUsageLandsInLifecycleRow() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_CACHE_USAGE).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_WITH_CACHE).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("input_tokens", 5L);
        assertThat(row).containsEntry("output_tokens", 12L);
        // Prompt-cache accounting (ADR-0022 §11 D1 / P0): clients are billed on
        // these two counts, so they must survive the whole pipeline.
        assertThat(row).containsEntry("cache_creation_input_tokens", 150L);
        assertThat(row).containsEntry("cache_read_input_tokens", 300L);
    }

    @Test
    @Order(5)
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
    @Order(6)
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
    @Order(7)
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
    @Order(8)
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

    /** Counts fixture-tenant lifecycle rows that started at or after the mark. */
    private int countLifecycleRowsSince(long mark) {
        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM request_usage_records
                WHERE tenant_id = :tenantId AND started_at >= :since
                """, new MapSqlParameterSource().addValue("tenantId", GatewayTestKeys.TENANT_ID).addValue("since",
                new java.sql.Timestamp(mark)), Integer.class);
        return rows == null ? 0 : rows;
    }

    /** Builds an Anthropic body of exactly {@code chars} ASCII characters. */
    private static String anthropicBodyOfLength(int chars) {
        String prefix = "{\"model\":\"claude-sonnet-5-20250915\",\"max_tokens\":1024,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"";
        String suffix = "\"}]}";
        int filler = chars - prefix.length() - suffix.length();
        assertThat(filler).isPositive();
        return prefix + "x".repeat(filler) + suffix;
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
