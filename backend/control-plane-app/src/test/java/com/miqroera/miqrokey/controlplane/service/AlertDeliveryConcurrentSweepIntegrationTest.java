package com.miqroera.miqrokey.controlplane.service;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two control-plane instances share one PostgreSQL, so both run the due-retry
 * sweep (each JVM has its own {@code @Scheduled} thread) and both are handed
 * the same due rows by that sweep's read.
 *
 * <p>
 * The delivery itself must still reach the receiver <em>once</em>. Measured
 * against two real instances (#1383): five outbound POSTs against a documented
 * cap of three, with only three rows in {@code webhook_delivery_attempts} —
 * {@link AlertEventDispatcher#recordAttempt}'s upsert on
 * {@code (event_id, endpoint_id, attempt)} folds the duplicate into the row the
 * survivor wrote, so the extra traffic never appears in the admin UI.
 * </p>
 *
 * <p>
 * "Instance" here is two independent {@link AlertEventDispatcher} instances
 * over the shared {@code DataSource}, swept from two threads at once — the same
 * race the two JVMs ran into, driven deterministically instead of with timing
 * luck. The receiver holds the first POST of the retry phase open until a
 * second one arrives (or 3s pass), so a sweep that decided to send cannot
 * finish — and release the other's SELECT — before the second sweep has decided
 * too.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Concurrent alert-retry sweeps across two control-plane instances (PostgreSQL)")
class AlertDeliveryConcurrentSweepIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // The mock receiver runs on loopback.
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
        // Slow the scheduled evaluator so tests drive evaluation explicitly.
        registry.add("miqrokey.alerts.evaluation-interval-ms", () -> "3600000");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    AlertEvaluator alertEvaluator;
    @Autowired
    AlertEventDispatcher dispatcher;
    @Autowired
    WebhookEndpointService endpointService;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private HttpServer mockReceiver;
    private String mockBaseUrl;
    private final AtomicInteger received = new AtomicInteger();
    /** Releases the first retry-phase POST once a second one shows up. */
    private final java.util.concurrent.CountDownLatch secondRetryArrived = new java.util.concurrent.CountDownLatch(1);
    /** Off during setup, on for the concurrent sweep. */
    private volatile boolean holdDeliveries;
    /**
     * POSTs seen <em>while holding</em>. Counted separately from {@link #received}:
     * the initial delivery has already happened by the time holding starts, so a
     * counter that includes it can never reach 1 while holding — which is how this
     * gate silently became a no-op (the first retry got {@code 2} and simply
     * released itself).
     */
    private final AtomicInteger heldRetries = new AtomicInteger();
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        holdDeliveries = false;
        heldRetries.set(0);
        mockReceiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mockReceiver.createContext("/hook", this::handleHook);
        mockReceiver.start();
        mockBaseUrl = "http://127.0.0.1:" + mockReceiver.getAddress().getPort() + "/hook";

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        if (mockReceiver != null) {
            mockReceiver.stop(0);
        }
        fx.reset();
    }

    private void handleHook(HttpExchange exchange) throws java.io.IOException {
        received.incrementAndGet();
        if (holdDeliveries) {
            // Count only the retry-phase POSTs (see heldRetries): the initial delivery is
            // already behind us, so `received` can never be 1 here.
            int held = heldRetries.incrementAndGet();
            if (held == 2) {
                secondRetryArrived.countDown();
            } else if (held == 1) {
                awaitSecondRetry();
            }
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(500, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void awaitSecondRetry() {
        try {
            // Bounded: with the fix there is no second POST to wait for, and the test
            // must still finish.
            secondRetryArrived.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a due retry swept by two instances at once reaches the receiver exactly once")
    void concurrentSweepsDeliverADueRetryOnce() throws Exception {
        String endpointId = createEndpoint();
        createRule(endpointId);
        fx.insertUsage(true);
        fx.insertUsage(false);

        alertEvaluator.evaluateAll();
        assertThat(received.get()).as("the first delivery is deduped by alert_events, which both instances share")
                .isEqualTo(1);
        // The first backoff is 2^1 × 60s in the future; pull it into the past so the
        // sweep sees the delivery as due.
        backdateRetry(endpointId);

        holdDeliveries = true;
        AlertEventDispatcher other = secondInstance();
        sweepConcurrently(dispatcher::retryDue, other::retryDue);

        assertThat(received.get()).as("the receiver must see the retry once, however many instances swept it")
                .isEqualTo(2);
        // And the audit table cannot be used to notice the difference: recordAttempt's
        // upsert folds a duplicate into the surviving row, which is exactly why #1383
        // showed five POSTs at the receiver and three rows in the database.
        assertThat(attemptsOf(endpointId)).as("one row per real attempt, no more").hasSize(2);
    }

    @Test
    @DisplayName("a retry claimed by one instance is left alone by a sweep that follows")
    void claimedRetryIsNotResweptWhileTheLeaseHolds() throws Exception {
        String endpointId = createEndpoint();
        createRule(endpointId);
        fx.insertUsage(true);
        fx.insertUsage(false);

        alertEvaluator.evaluateAll();
        backdateRetry(endpointId);

        dispatcher.retryDue();
        assertThat(received.get()).isEqualTo(2);

        // The claim sits on the attempt row's own backoff deadline, so a later sweep —
        // the loser of the race, or the same instance's next tick — finds nothing due.
        dispatcher.retryDue();
        assertThat(received.get()).as("a claimed delivery must not be sent twice by later sweeps").isEqualTo(2);
        assertThat(attemptsOf(endpointId)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The second control-plane instance: same database, its own dispatcher state.
     */
    private AlertEventDispatcher secondInstance() {
        return new AlertEventDispatcher(jdbc, endpointService, objectMapper);
    }

    private void sweepConcurrently(Runnable... sweeps) throws Exception {
        var pool = Executors.newFixedThreadPool(sweeps.length);
        try {
            CyclicBarrier start = new CyclicBarrier(sweeps.length);
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable sweep : sweeps) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    sweep.run();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String createEndpoint() throws Exception {
        MvcResult created = mockMvc
                .perform(
                        post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                                .content(objectMapper.writeValueAsString(Map.of("name",
                                        "receiver-" + UUID.randomUUID().toString().substring(0, 8), "url", mockBaseUrl,
                                        "secret", "whsec-concurrent-sweep-test", "timeoutMs", 30000))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private String createRule(String endpointId) throws Exception {
        MvcResult created = mockMvc
                .perform(
                        post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                                .content(objectMapper.writeValueAsString(Map.of("name",
                                        "missing-rate-" + UUID.randomUUID().toString().substring(0, 8), "type",
                                        "USAGE_MISSING_RATE", "threshold", 0.5, "webhookEndpointId", endpointId))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    /** Pulls the armed backoff deadline into the past so a sweep sees it as due. */
    private void backdateRetry(String endpointId) {
        int backdated = jdbc.update(
                "UPDATE webhook_delivery_attempts SET next_retry_at = now() - interval '1 minute' "
                        + "WHERE endpoint_id = :endpointId",
                new MapSqlParameterSource("endpointId", UUID.fromString(endpointId)));
        assertThat(backdated).as("the failed attempt must have a backoff deadline to pull back").isEqualTo(1);
    }

    private List<Map<String, Object>> attemptsOf(String endpointId) {
        return jdbc.query(
                "SELECT attempt, http_status, next_retry_at FROM webhook_delivery_attempts "
                        + "WHERE endpoint_id = :endpointId ORDER BY attempt",
                new MapSqlParameterSource("endpointId", UUID.fromString(endpointId)), (rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("attempt", rs.getInt("attempt"));
                    row.put("http_status", rs.getObject("http_status"));
                    row.put("next_retry_at", rs.getTimestamp("next_retry_at"));
                    return row;
                });
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        void reset() {
            // Child-first, same list as the sibling alert integration tests: the
            // container is shared, so only this domain's rows are removed.
            for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                    "usage_event", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertUsage(boolean usageMissing) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, 'req-' || :id, '00000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
                            'model-a', 'UPSTREAM', 10, 5, 15, 42, 200, TRUE, :usageMissing, 'greq', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                    .addValue("usageMissing", usageMissing));
        }
    }

    static class BootstrapHelper {
        static final java.nio.file.Path SECRET_FILE;
        static final String SECRET = "test-bootstrap-secret-min-16chars";
        static {
            try {
                SECRET_FILE = java.nio.file.Files.createTempFile("bootstrap-secret", ".txt");
                java.nio.file.Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static java.nio.file.Path secretFile() {
            return SECRET_FILE;
        }

        static String secret() {
            return SECRET;
        }
    }
}
