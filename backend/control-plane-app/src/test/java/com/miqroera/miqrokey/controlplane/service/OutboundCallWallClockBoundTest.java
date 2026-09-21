package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.client.OpenRouterPriceSourceClient;
import com.miqroera.miqrokey.controlplane.config.PriceSyncProperties;
import com.miqroera.miqrokey.domain.model.InternalService;
import com.miqroera.miqrokey.domain.model.McpService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PH57 wall-clock bound sweep: every outbound call must be bounded as a
 * <em>whole operation</em>, not only up to the response headers.
 *
 * <p>
 * {@code HttpRequest.timeout(..)} on the JDK client bounds the wait for the
 * response <em>headers</em> only. A peer that answers {@code 200} with a
 * {@code Content-Length} and then stops writing leaves the subsequent body read
 * blocked forever, however small the configured timeout is. Each test below
 * drives a real production class at such a peer and measures the actual wall
 * clock against the value the caller configured.
 * </p>
 *
 * <p>
 * The peer is in-process by default so the suite is self-contained; set
 * {@code -Dph57.peer.url=http://127.0.0.1:&lt;port&gt;} to point the same
 * measurements at an external slow peer serving the same paths.
 * </p>
 */
@DisplayName("PH57 outbound call wall-clock bounds")
class OutboundCallWallClockBoundTest {

    /** Configured per-call budget handed to the production classes. */
    private static final int CONFIGURED_TIMEOUT_SECONDS = 3;

    /**
     * How long a measurement is allowed to run before it is recorded as
     * "still blocked". Kept well above the configured timeout so a working
     * bound (which returns at ~3 s) is never mistaken for a hang.
     */
    private static final long OBSERVATION_WINDOW_MS = 12_000;

    private final AtomicLong stallLatch = new AtomicLong();

    private HttpServer stalledPeer;
    private String stalledBaseUrl;

    @BeforeEach
    void startStalledPeer() throws Exception {
        String external = System.getProperty("ph57.peer.url");
        if (external != null && !external.isBlank()) {
            stalledBaseUrl = external;
            return;
        }
        stalledPeer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalledPeer.createContext("/stall-body", exchange -> {
            // 200 + a declared length, then a fraction of it and a hold: the
            // client sees its response headers well before any timeout fires.
            exchange.sendResponseHeaders(200, 1000);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write("0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                body.flush();
                stallLatch.incrementAndGet();
                Thread.sleep(600_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        stalledPeer.createContext("/no-headers", exchange -> {
            stallLatch.incrementAndGet();
            try {
                Thread.sleep(600_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        stalledPeer.start();
        stalledBaseUrl = "http://127.0.0.1:" + stalledPeer.getAddress().getPort();
    }

    @AfterEach
    void stopStalledPeer() {
        if (stalledPeer != null) {
            stalledPeer.stop(0);
        }
    }

    // -----------------------------------------------------------------
    // C1 — ServiceHealthChecker.isHealthy (services health cycle)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("C1 ServiceHealthChecker.isHealthy is bounded by checkTimeoutSeconds end to end")
    void serviceHealthCheckerIsBounded() throws Exception {
        ServiceHealthChecker checker = new ServiceHealthChecker(null);
        InternalService service = internalService("/stall-body");

        long elapsed = measure("ServiceHealthChecker.isHealthy", () -> checker.isHealthy(service));

        assertBounded("ServiceHealthChecker.isHealthy", elapsed);
    }

    // -----------------------------------------------------------------
    // C2/C3 — McpHealthChecker probes (MCP health cycle + 「调用验证」)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("C2 McpHealthChecker health-path probe is bounded by checkTimeoutSeconds end to end")
    void mcpHealthPathProbeIsBounded() throws Exception {
        McpHealthChecker checker = new McpHealthChecker(null, null);
        McpService service = mcpService(stalledBaseUrl, "/stall-body", McpService.CHECK_MODE_HEALTH_PATH);

        long elapsed = measure("McpHealthChecker.probeOnce(HEALTH_PATH)", () -> checker.probeOnce(service));

        assertBounded("McpHealthChecker.probeOnce(HEALTH_PATH)", elapsed);
    }

    @Test
    @DisplayName("C3 McpHealthChecker JSON-RPC probe is bounded by checkTimeoutSeconds end to end")
    void mcpJsonRpcProbeIsBounded() throws Exception {
        McpHealthChecker checker = new McpHealthChecker(null, null);
        McpService service = mcpService(stalledBaseUrl + "/stall-body", "/stall-body",
                McpService.CHECK_MODE_JSONRPC);

        long elapsed = measure("McpHealthChecker.probeOnce(JSONRPC_INITIALIZE)",
                () -> checker.probeOnce(service));

        assertBounded("McpHealthChecker.probeOnce(JSONRPC_INITIALIZE)", elapsed);
    }

    // -----------------------------------------------------------------
    // C4 — McpToolsListClient.fetchTools (tools/list import)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("C4 McpToolsListClient.fetchTools is bounded by its request timeout end to end")
    void mcpToolsListFetchIsBounded() throws Exception {
        McpToolsListClient client = new McpToolsListClient(new ObjectMapper(),
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONFIGURED_TIMEOUT_SECONDS))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(CONFIGURED_TIMEOUT_SECONDS));

        long elapsed = measure("McpToolsListClient.fetchTools",
                () -> client.fetchTools(stalledBaseUrl + "/stall-body", null));

        assertBounded("McpToolsListClient.fetchTools", elapsed);
    }

    // -----------------------------------------------------------------
    // C6 — OpenRouterPriceSourceClient.fetch (price catalog sync)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("C6 OpenRouterPriceSourceClient.fetch is bounded by its request timeout end to end")
    void priceSourceFetchIsBounded() throws Exception {
        PriceSyncProperties properties = new PriceSyncProperties();
        properties.setUrl(stalledBaseUrl + "/stall-body");
        properties.setConnectTimeout(Duration.ofSeconds(CONFIGURED_TIMEOUT_SECONDS));
        properties.setRequestTimeout(Duration.ofSeconds(CONFIGURED_TIMEOUT_SECONDS));
        OpenRouterPriceSourceClient client = new OpenRouterPriceSourceClient(new ObjectMapper(), properties);

        long elapsed = measure("OpenRouterPriceSourceClient.fetch", client::fetch);

        assertBounded("OpenRouterPriceSourceClient.fetch", elapsed);
    }

    // -----------------------------------------------------------------
    // Amplification — one stalled call on the shared single-thread scheduler
    // -----------------------------------------------------------------

    @Test
    @DisplayName("A stalled probe must not stall unrelated work on the control plane's scheduler")
    void stalledProbeDoesNotStallUnrelatedScheduledWork() throws Exception {
        // Spring Boot's default @EnableScheduling scheduler is single-threaded
        // (no TaskScheduler bean, no spring.task.scheduling.pool.size anywhere
        // in this application): whatever blocks one job blocks them all.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ph57-scheduler-");
        scheduler.initialize();

        CountDownLatch released = new CountDownLatch(1);
        try {
            ServiceHealthChecker checker = new ServiceHealthChecker(null);
            InternalService stalled = internalService("/stall-body");

            ScheduledFuture<?> healthCycle = scheduler.scheduleWithFixedDelay(
                    () -> checker.isHealthy(stalled), Duration.ofMillis(200));

            // Let the probe reach the stalled body read and take the only thread.
            long queuedAt = System.nanoTime();
            ScheduledFuture<?> unrelated = scheduler.schedule(released::countDown, Instant.now());
            boolean ran = awaitWithin(released, OBSERVATION_WINDOW_MS / 1000);
            long blockedMs = (System.nanoTime() - queuedAt) / 1_000_000;

            System.out.printf("[ph57] %-42s unrelated scheduled job waited %6d ms%n",
                    "scheduler amplification", blockedMs);

            assertThat(ran)
                    .as("an unrelated job queued behind one stalled health probe on the shared "
                            + "single-thread scheduler (waited %d ms)", blockedMs)
                    .isTrue();
        } finally {
            released.countDown();
            scheduler.shutdown();
        }
    }

    // -----------------------------------------------------------------

    private InternalService internalService(String checkPath) {
        return new InternalService(UUID.randomUUID(), UUID.randomUUID(), "ph57-peer", "HTTP", null, stalledBaseUrl,
                "ACTIVE", 0, UUID.randomUUID(), Instant.now(), Instant.now(), "UNKNOWN", null, 0, 0,
                30, CONFIGURED_TIMEOUT_SECONDS, 3, 1, checkPath);
    }

    private McpService mcpService(String endpoint, String checkPath, String checkMode) {
        return new McpService(UUID.randomUUID(), UUID.randomUUID(), "ph57-peer", "PH57 stalled peer",
                endpoint, "STREAMABLE_HTTP", "ONLINE", "UNKNOWN", null, 0, 0,
                30, CONFIGURED_TIMEOUT_SECONDS, 3, 1, checkPath, 0, UUID.randomUUID(), Instant.now(), Instant.now(),
                "VISITOR", null, McpService.DEFAULT_UPSTREAM_TIMEOUT_MS, checkMode);
    }

    /**
     * Runs {@code call} on its own thread and reports the wall clock. A call
     * still running when the observation window closes is reported as a
     * negative value, which fails {@link #assertBounded} with the honest
     * lower bound rather than a fabricated number.
     */
    private static long measure(String label, Runnable call) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ph57-measure");
            thread.setDaemon(true);
            return thread;
        });
        long start = System.nanoTime();
        Future<?> future = executor.submit(call);
        try {
            future.get(OBSERVATION_WINDOW_MS, TimeUnit.MILLISECONDS);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[ph57] %-42s returned after %6d ms (configured %d s)%n", label, elapsed,
                    CONFIGURED_TIMEOUT_SECONDS);
            return elapsed;
        } catch (java.util.concurrent.TimeoutException e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[ph57] %-42s STILL BLOCKED after %6d ms (configured %d s) — call never returned%n",
                    label, elapsed, CONFIGURED_TIMEOUT_SECONDS);
            return -elapsed;
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    private static void assertBounded(String label, long measured) {
        long limitMs = CONFIGURED_TIMEOUT_SECONDS * 1000L + 2_000;
        if (measured < 0) {
            assertThat(-measured)
                    .as("%s: configured timeout is %d s but the call was still blocked when the %d ms observation "
                            + "window closed — the configured value does not bound the whole call",
                            label, CONFIGURED_TIMEOUT_SECONDS, OBSERVATION_WINDOW_MS)
                    .isLessThan(limitMs);
        } else {
            assertThat(measured)
                    .as("%s: configured timeout is %d s", label, CONFIGURED_TIMEOUT_SECONDS)
                    .isLessThan(limitMs);
        }
    }

    private static boolean awaitWithin(CountDownLatch latch, long seconds) throws InterruptedException {
        return latch.await(seconds, TimeUnit.SECONDS);
    }
}
