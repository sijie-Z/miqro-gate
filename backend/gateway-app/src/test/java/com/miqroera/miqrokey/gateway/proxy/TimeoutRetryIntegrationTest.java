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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBuffer;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2.5 network bounds end-to-end: connection/first-byte/stream-idle/overall
 * timeouts, the at-most-once pre-first-byte retry, and bounded memory on slow
 * clients. Every request that reaches upstream lands in
 * {@code request_usage_records} with the correct terminal status.
 *
 * <p>
 * The fixture route snapshot ({@link GatewayAuthTestConfig}) points at the mock
 * provider. Timeouts are shortened via static properties so the tests run in
 * seconds: connect 2s, first-byte 2s, stream-idle 2s, overall 4s.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=true", "miqrokey.gateway.route-snapshot.refresh-interval=1h",
        "spring.flyway.enabled=true", "miqrokey.gateway.upstream.connect-timeout=PT2S",
        "miqrokey.gateway.upstream.first-byte-timeout=PT2S", "miqrokey.gateway.upstream.stream-idle-timeout=PT2S",
        "miqrokey.gateway.upstream.response-timeout=PT4S"})
@Import(GatewayAuthTestConfig.class)
@Tag("integration")
@DisplayName("Timeout, retry and backpressure end-to-end (gateway + PostgreSQL)")
class TimeoutRetryIntegrationTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName
                .parse("postgres:17.6-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
                .withPassword("miqrokey_test");
        POSTGRES.start();
    }

    private static final Path ENC_KEY_FILE = KeyFiles.write("timeout-test-enc.key");
    private static final Path HMAC_KEY_FILE = KeyFiles.write("timeout-test-hmac.key");

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
        // beforeAll-time, so migrate manually, then seed the fixture tenant.
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .update("""
                        INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                        VALUES (:id, 'timeout-test', 'Timeout Test', 'ACTIVE', 0, now(), now())
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
    // Retry (at most once, only before the first byte)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("a connection failure is retried once and the retry succeeds (retry_count=1)")
    void connectionFailureRetriesOnceThenSucceeds() throws Exception {
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("application/json")
                        .header("x-request-id", "req-retry-ok").body(AnthropicFixtures.RESPONSE_BASIC).build());
        mockProvider.disconnectNextRequest();

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();

        // Two attempts reached the mock; the disconnected first attempt is not
        // captured, the successful retry is.
        assertThat(mockProvider.getCapturedRequests()).hasSize(1);
        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("http_status", 200);
        assertThat(row).containsEntry("retry_count", 1);
        assertThat(row).containsEntry("client_cancelled", false);
    }

    @Test
    @DisplayName("a persistent connection failure retries once and finalizes UPSTREAM_UNAVAILABLE (502)")
    void persistentConnectionFailureFinalizesUnavailableRow() throws Exception {
        mockProvider.disconnectAllRequests();

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isEqualTo(502).expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "UPSTREAM_UNAVAILABLE");
        assertThat(row.get("http_status")).isNull();
        assertThat(row).containsEntry("retry_count", 1);
    }

    @Test
    @DisplayName("a 200 upstream response still records retry_count=0")
    void successRecordsZeroRetries() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("retry_count", 0);
    }

    // -------------------------------------------------------------------
    // Timeouts
    // -------------------------------------------------------------------

    @Test
    @DisplayName("a slow first byte finalizes TIMEOUT_BEFORE_FIRST_BYTE and is not retried")
    void firstByteTimeoutFinalizesTimeoutBeforeFirstByte() throws Exception {
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("application/json")
                        .body(AnthropicFixtures.RESPONSE_BASIC).responseDelay(Duration.ofSeconds(10)).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isEqualTo(502).expectBody().returnResult().getResponseBody();

        // Timeouts are not retried: exactly one request reached the mock.
        assertThat(mockProvider.getCapturedRequests()).hasSize(1);
        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "TIMEOUT_BEFORE_FIRST_BYTE");
        assertThat(row.get("http_status")).isNull();
        assertThat(row).containsEntry("retry_count", 0);
        assertThat(row.get("first_byte_at")).isNull();
    }

    @Test
    @DisplayName("a stalled mid-stream response finalizes STREAM_INTERRUPTED with partial_response")
    void streamIdleTimeoutFinalizesStreamInterrupted() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("text/event-stream").body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                .chunkDelay(Duration.ofMillis(50)).haltAfterLines(1).build());

        AtomicReference<Throwable> clientError = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicInteger receivedBytes = new AtomicInteger();
        Flux<DataBuffer> responseBody = WebClient.create("http://localhost:" + gatewayPort).post().uri("/v1/messages")
                .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                .bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                .exchangeToFlux(response -> response.bodyToFlux(DataBuffer.class));
        responseBody.subscribe(buffer -> {
            receivedBytes.addAndGet(buffer.readableByteCount());
            DataBufferUtils.release(buffer);
        }, clientError::set, () -> completed.set(true));

        assertTerminatedWithin(Duration.ofSeconds(10), completed, clientError);
        // The first chunk arrived, then the connection was cut by the gateway's
        // stream-idle timeout — the client observes an error, not a clean end.
        assertThat(receivedBytes.get()).isGreaterThan(0);
        assertThat(clientError.get()).isNotNull();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "STREAM_INTERRUPTED");
        assertThat(row).containsEntry("http_status", 200);
        assertThat(row).containsEntry("streaming", true);
        assertThat(row).containsEntry("partial_response", true);
        assertThat(row).containsEntry("client_cancelled", false);
        assertThat(row.get("first_byte_at")).isNotNull();
    }

    @Test
    @DisplayName("a long stream that never finishes hits the overall deadline and finalizes STREAM_INTERRUPTED")
    void overallDeadlineFinalizesStreamInterrupted() throws Exception {
        String manyEvents = "data: {\"type\":\"ping\"}\n\n".repeat(20);
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                        .body(manyEvents).streaming(true).chunkDelay(Duration.ofMillis(400)).build());

        AtomicReference<Throwable> clientError = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicLong receivedBytes = new AtomicLong();
        Flux<DataBuffer> responseBody = WebClient.create("http://localhost:" + gatewayPort).post().uri("/v1/messages")
                .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                .bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                .exchangeToFlux(response -> response.bodyToFlux(DataBuffer.class));
        responseBody.subscribe(buffer -> {
            receivedBytes.addAndGet(buffer.readableByteCount());
            DataBufferUtils.release(buffer);
        }, clientError::set, () -> completed.set(true));

        assertTerminatedWithin(Duration.ofSeconds(10), completed, clientError);
        // Chunks kept arriving (idle timeout never fired) but the 4s overall
        // deadline cut the stream before all 20 events (≈8s) were sent.
        assertThat(receivedBytes.get()).isGreaterThan(0);
        assertThat(receivedBytes.get()).isLessThan(manyEvents.getBytes(StandardCharsets.UTF_8).length);
        assertThat(clientError.get()).isNotNull();

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "STREAM_INTERRUPTED");
        assertThat(row).containsEntry("http_status", 200);
        assertThat(row).containsEntry("partial_response", true);
    }

    // -------------------------------------------------------------------
    // Slow client: memory stays bounded (streaming, no aggregation)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("a slow client still receives a response larger than the proxy buffer (streamed, not buffered)")
    void slowClientReceivesFullLargeResponse() throws Exception {
        // 512KB > maxProxyBuffer (256KB): the collector overflows (and skips
        // caching) but the transparent stream to the client must not be
        // affected — the response is streamed chunk by chunk, never aggregated.
        String bigBody = "{\"id\":\"msg_big\",\"content\":\"" + "a".repeat(512 * 1024) + "\"}";
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(bigBody).build());

        AtomicReference<Throwable> clientError = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicLong receivedBytes = new AtomicLong();
        Flux<DataBuffer> responseBody = WebClient.create("http://localhost:" + gatewayPort).post().uri("/v1/messages")
                .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING)
                .exchangeToFlux(response -> response.bodyToFlux(DataBuffer.class));
        responseBody.subscribe(buffer -> {
            receivedBytes.addAndGet(buffer.readableByteCount());
            DataBufferUtils.release(buffer);
            // Slow consumer: blocking here backs the write side up through the
            // gateway to the mock — the gateway must stream, not buffer.
            try {
                Thread.sleep(15);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, clientError::set, () -> completed.set(true));

        assertTerminatedWithin(Duration.ofSeconds(15), completed, clientError);
        assertThat(completed.get()).isTrue();
        assertThat(clientError.get()).isNull();
        assertThat(receivedBytes.get()).isEqualTo(bigBody.getBytes(StandardCharsets.UTF_8).length);

        Map<String, Object> row = awaitLatestLifecycleRow();
        assertThat(row).containsEntry("request_status", "SUCCEEDED");
        assertThat(row).containsEntry("http_status", 200);
        assertThat(row).containsEntry("usage_missing", true);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private void assertTerminatedWithin(Duration timeout, AtomicBoolean completed, AtomicReference<Throwable> error)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (completed.get() || error.get() != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Client stream did not terminate within " + timeout);
    }

    /**
     * Flushes the bus and polls for the latest lifecycle row of the fixture key
     * finalized at or after the current test's start.
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
