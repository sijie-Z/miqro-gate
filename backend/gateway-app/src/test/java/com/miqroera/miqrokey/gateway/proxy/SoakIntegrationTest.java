package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import io.netty.channel.ChannelOption;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §10 performance acceptance, CI variant (G6.4 / #414): a real
 * {@value #WINDOW_SECONDS}-second window of <b>{@value #CONCURRENCY} concurrent
 * streaming SSE requests</b> (the first-version envelope) against the real
 * gateway and a streaming mock upstream, asserting:
 *
 * <ol>
 * <li>zero errors;</li>
 * <li>gateway-added first-byte overhead P95 within the CI gate
 * ({@value #CI_FIRST_BYTE_P95_BUDGET_MS} ms on shared runners; the §10 product
 * SLO of {@value #FIRST_BYTE_P95_BUDGET_MS} ms is verified by {@code soak.sh}
 * on representative hardware) — observed TTFB minus the upstream's own
 * first-byte delay;</li>
 * <li>event-loop responsiveness: a lightweight probe keeps answering with
 * bounded worst-case latency while the window runs (no blocking);</li>
 * <li>usage rows written exactly once per request (no drops, no duplicates) —
 * the queue-loss detector.</li>
 * </ol>
 *
 * The long-duration variant for production-like environments is
 * {@code deploy/loadtest/soak.sh} ({@code MQK_CONCURRENCY=50} = the red-line
 * profile); this window is deliberately short so CI stays fast.
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
@DisplayName("Soak test (G6.4, §10 performance acceptance)")
class SoakIntegrationTest {

    /**
     * First-version envelope (testing-and-acceptance §10): 50 concurrent streams.
     */
    private static final int CONCURRENCY = 50;

    /** Sustained window length; the sh script is the long-duration variant. */
    private static final int WINDOW_SECONDS = 10;

    /**
     * §10 product SLO, deployment-representative hardware: gateway-added first-byte
     * overhead P95 ≤ 30 ms (verified by {@code soak.sh} on a quiet box; local dev
     * with this exact fixture measures p95 = 23–26 ms).
     */
    private static final long FIRST_BYTE_P95_BUDGET_MS = 30;

    /**
     * CI gate for the same metric: shared 4-vCPU runners co-schedule the 50 in-JVM
     * client threads, the gateway and the mock, so the observed metric carries host
     * contention (2026-09-12: p50=25 ms / p95=61 ms on CI vs p50=11 ms / p95=26 ms
     * on an 8-core dev box). The CI bound still catches order-of-magnitude
     * regressions — an event-loop block pushes P95 into seconds.
     */
    private static final long CI_FIRST_BYTE_P95_BUDGET_MS = 150;

    /** §10: no event-loop blocking — probe worst case must stay under this. */
    private static final long PROBE_MAX_MS_BUDGET = 500;

    /** The mock upstream's own first-byte delay, subtracted from observed TTFB. */
    private static final long UPSTREAM_FIRST_BYTE_DELAY_MS = 50;

    /**
     * Per-stream SSE trickle: lines arrive every 20 ms (a real stream, not one
     * blob).
     */
    private static final Duration STREAM_CHUNK_DELAY = Duration.ofMillis(20);

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
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
    @DisplayName("50 concurrent streams over a sustained window: no errors, P95 first-byte within budget, usage exactly once")
    void concurrentStreamsRemainHealthy() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("text/event-stream").body(AnthropicFixtures.RESPONSE_STREAMING_SSE)
                .responseDelay(Duration.ofMillis(UPSTREAM_FIRST_BYTE_DELAY_MS)).chunkDelay(STREAM_CHUNK_DELAY).build());

        List<StreamResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        AtomicLong probeMaxMs = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        ExecutorService probe = Executors.newSingleThreadExecutor();
        CountDownLatch start = new CountDownLatch(1);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(WINDOW_SECONDS);

        ConnectionProvider connections = ConnectionProvider.builder("soak").maxConnections(CONCURRENCY * 2)
                .pendingAcquireMaxCount(CONCURRENCY * 4).build();
        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + gatewayPort)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        HttpClient.create(connections).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)))
                .build();

        Future<?> probeFuture = probe.submit(() -> {
            awaitQuietly(start);
            while (System.nanoTime() < deadlineNanos) {
                long t0 = System.nanoTime();
                try {
                    // Invalid key: rejected at the gateway before any upstream call —
                    // a pure event-loop responsiveness probe under load.
                    webTestClient.post().uri("/v1/messages")
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented() + "x")
                            .contentType(MediaType.APPLICATION_JSON).bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                            .exchange().expectBody().returnResult();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                probeMaxMs.accumulateAndGet(elapsedMs, Math::max);
                sleepQuietly(500);
            }
        });

        List<Callable<Void>> workers = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            workers.add(() -> {
                awaitQuietly(start);
                while (System.nanoTime() < deadlineNanos) {
                    streamOnce(client, results, errors);
                }
                return null;
            });
        }
        List<Future<Void>> workerFutures = new ArrayList<>();
        for (Callable<Void> worker : workers) {
            workerFutures.add(pool.submit(worker));
        }
        start.countDown();
        for (Future<Void> future : workerFutures) {
            future.get(WINDOW_SECONDS + 60, TimeUnit.SECONDS);
        }
        probeFuture.get(WINDOW_SECONDS + 30, TimeUnit.SECONDS);
        pool.shutdown();
        probe.shutdown();
        connections.dispose();

        long sent = results.size() + errors.get();
        long ok = results.size();
        List<Long> overheads = new ArrayList<>();
        long maxTotal = 0;
        for (StreamResult result : results) {
            overheads.add(result.ttfbMs() - UPSTREAM_FIRST_BYTE_DELAY_MS);
            maxTotal = Math.max(maxTotal, result.totalMs());
        }
        Collections.sort(overheads);
        long p50 = percentile(overheads, 0.50);
        long p95 = percentile(overheads, 0.95);
        System.out.printf(
                "soak: window=%ds concurrency=%d sent=%d ok=%d errors=%d%n"
                        + "ttfb-overhead ms: p50=%d p95=%d max-stream=%dms; probe max=%dms%n",
                WINDOW_SECONDS, CONCURRENCY, sent, ok, errors.get(), p50, p95, maxTotal, probeMaxMs.get());

        assertThat(errors.get()).as("zero errors across the window").isZero();
        assertThat(sent).as("the window must actually exercise the envelope").isGreaterThanOrEqualTo(200);
        assertThat(p95)
                .as("gateway-added first-byte overhead P95 (CI gate ≤ %d ms; §10 product SLO ≤ %d ms)",
                        CI_FIRST_BYTE_P95_BUDGET_MS, FIRST_BYTE_P95_BUDGET_MS)
                .isLessThanOrEqualTo(CI_FIRST_BYTE_P95_BUDGET_MS);
        assertThat(probeMaxMs.get()).as("event loop stays responsive (§10)").isLessThanOrEqualTo(PROBE_MAX_MS_BUDGET);

        // Usage rows: exactly one per successful request once the async writer
        // flushes — equality proves both no drops and no duplicates (§10).
        long rows = 0;
        for (int attempt = 0; attempt < 60 && rows != ok; attempt++) {
            Long counted = jdbc.queryForObject("SELECT count(*) FROM request_usage_records WHERE tenant_id = :tenantId",
                    new MapSqlParameterSource("tenantId", GatewayTestKeys.TENANT_ID), Long.class);
            rows = counted == null ? 0 : counted;
            if (rows != ok) {
                Thread.sleep(500);
            }
        }
        assertThat(rows).as("usage rows must equal successful requests (sent=%d, ok=%d)", sent, ok).isEqualTo(ok);

        mockProvider.reset();
    }

    /** One streaming request; records TTFB (first body chunk) and completion. */
    private static void streamOnce(WebClient client, List<StreamResult> results, AtomicLong errors) {
        long t0 = System.nanoTime();
        AtomicLong ttfbNanos = new AtomicLong(-1);
        try {
            Integer status = client.post().uri("/v1/messages")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                    .exchangeToMono(response -> response.bodyToFlux(String.class)
                            .doOnNext(chunk -> ttfbNanos.compareAndSet(-1, System.nanoTime() - t0))
                            .then(Mono.just(response.statusCode().value())))
                    .block(Duration.ofSeconds(30));
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            if (status != null && status == 200 && ttfbNanos.get() > 0) {
                results.add(new StreamResult(ttfbNanos.get() / 1_000_000, totalMs, status));
            } else {
                errors.incrementAndGet();
            }
        } catch (Exception e) {
            errors.incrementAndGet();
        }
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int index = Math.min((int) Math.ceil(q * sorted.size()) - 1, sorted.size() - 1);
        return sorted.get(Math.max(index, 0));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record StreamResult(long ttfbMs, long totalMs, int status) {
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
