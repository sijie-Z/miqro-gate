package com.miqroera.miqrokey.controlplane.service;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The configured per-endpoint {@code timeoutMs} must bound the <em>whole</em>
 * delivery call, not just the wait for response headers.
 *
 * <p>
 * The receiver here is a raw socket, not a {@code com.sun.net.httpserver}
 * handler, because the scenario needs byte-level control: it answers
 * {@code HTTP/1.1 200 OK} with {@code Content-Length: 1000}, writes 10 bytes of
 * body, flushes, and then holds the connection open without ever finishing the
 * body. A receiver that sends its headers promptly is not "slow to respond" in
 * the header sense — it is a perfectly legal-looking 200 whose body never
 * completes, and it is the shape a wedged or half-dead proxy produces.
 * </p>
 *
 * <p>
 * The delivery runs on a worker thread so the test measures the wall clock of
 * {@link AlertEvaluator#evaluateAll()} itself. That method is the control
 * plane's {@code @Scheduled} entry point and runs on the single-threaded
 * default scheduler together with every other periodic job in the application,
 * so a delivery that does not return does not merely lose one alert.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Alert delivery against a receiver that stalls its response body (raw socket)")
class AlertDeliveryStalledResponseBodyIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    /** Endpoint timeout used by this test; the documented minimum. */
    private static final int ENDPOINT_TIMEOUT_MS = 1_000;
    /**
     * Wall-clock budget for the whole evaluation. Generous next to the 1s endpoint
     * timeout (rule load + event insert + attempt insert + HTTP), yet far below the
     * receiver's 120s stall, so red and green are unambiguous.
     */
    private static final long EVALUATION_BUDGET_MS = 5_000;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // The receiver runs on loopback.
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
        // Slow the scheduled evaluator so the test drives evaluation explicitly.
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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;

    private ServerSocket serverSocket;
    private volatile Socket acceptedSocket;
    private final CountDownLatch receiverRelease = new CountDownLatch(1);
    private final AtomicInteger received = new AtomicInteger();
    /** Receiver-side raw evidence, surfaced in the failure message. */
    private final List<String> receiverLog = Collections.synchronizedList(new ArrayList<>());
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();

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
        receiverRelease.countDown();
        closeReceiver();
        fx.reset();
    }

    @Test
    @DisplayName("a 200 whose body never completes is bounded by the endpoint timeout and recorded as a failed attempt")
    void stalledResponseBodyIsBoundedByTheEndpointTimeout() throws Exception {
        String baseUrl = startStallingReceiver();
        String endpointId = createEndpoint(baseUrl);
        createRule(endpointId);
        fx.insertUsage(true);
        fx.insertUsage(false);

        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "alert-evaluation-under-test");
            t.setDaemon(true);
            return t;
        });
        long startedAt = System.currentTimeMillis();
        try {
            Future<?> evaluation = worker.submit(() -> alertEvaluator.evaluateAll());
            try {
                evaluation.get(EVALUATION_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - startedAt;
                evaluation.cancel(true);
                fail("AlertEvaluator.evaluateAll() had not returned after " + elapsed + " ms, although the endpoint's"
                        + " configured timeoutMs is " + ENDPOINT_TIMEOUT_MS + " ms and the whole-call budget for this"
                        + " test is " + EVALUATION_BUDGET_MS + " ms. The receiver answered with 200 headers"
                        + " (Content-Length: 1000), sent 10 body bytes and then stalled without closing, so the"
                        + " delivery is stuck reading the response body.\nreceiver-side raw log:\n  "
                        + String.join("\n  ", receiverLog) + "\nwebhook_delivery_attempts rows so far: "
                        + attemptRows(endpointId));
            }

            long elapsed = System.currentTimeMillis() - startedAt;
            assertThat(received.get()).as("the receiver must have been called once").isEqualTo(1);
            assertThat(receiverLog).anySatisfy(line -> assertThat(line).contains("stalling with body 10/1000"));
            Map<String, Object> row = onlyAttempt(endpointId);
            assertThat(row.get("http_status")).as("an unread response body is not an accepted delivery").isNull();
            assertThat(row.get("next_retry_at")).as("a stalled body must arm the documented backoff retry").isNotNull();
            assertThat(row.get("error_message")).as("a failed delivery must record a scrubbed reason").isNotNull();
            assertThat(elapsed).as("the failure must be bounded by the endpoint timeout, not by the receiver")
                    .isLessThan(EVALUATION_BUDGET_MS);
        } finally {
            receiverRelease.countDown();
            closeReceiver();
            worker.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // raw-socket receiver
    // ------------------------------------------------------------------

    /**
     * Accepts one connection, reads the request fully (head + Content-Length body),
     * then answers with 200 headers and an incomplete body.
     */
    private String startStallingReceiver() throws Exception {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try {
                Socket socket = serverSocket.accept();
                acceptedSocket = socket;
                long t0 = System.currentTimeMillis();
                String head = readRequest(socket);
                received.incrementAndGet();
                receiverLog.add("+" + (System.currentTimeMillis() - t0) + " ms request fully read: "
                        + (head.isEmpty() ? "<none>" : head.split("\r\n")[0]));
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.write("0123456789".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                receiverLog.add("+" + (System.currentTimeMillis() - t0) + " ms 200 headers + 10/1000 body bytes sent,"
                        + " stalling with body 10/1000 incomplete (socket left open)");
                // Hold the connection open without ever completing the body.
                receiverRelease.await(120, TimeUnit.SECONDS);
                receiverLog.add("+" + (System.currentTimeMillis() - t0) + " ms receiver released by the test");
            } catch (Exception e) {
                receiverLog.add("receiver ended: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }, "stalling-webhook-receiver");
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/hook";
    }

    /** Reads the request head, then exactly Content-Length body bytes. */
    private static String readRequest(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            head.write(c);
            byte[] b = head.toByteArray();
            int n = b.length;
            if (n >= 4 && b[n - 4] == '\r' && b[n - 3] == '\n' && b[n - 2] == '\r' && b[n - 1] == '\n') {
                break;
            }
        }
        String h = head.toString(StandardCharsets.ISO_8859_1);
        int contentLength = 0;
        for (String line : h.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        if (contentLength > 0) {
            in.readNBytes(contentLength);
        }
        return h;
    }

    private void closeReceiver() {
        Socket accepted = acceptedSocket;
        if (accepted != null) {
            try {
                accepted.close();
            } catch (Exception ignored) {
                // The test is already reporting its result.
            }
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
                // The test is already reporting its result.
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String createEndpoint(String url) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name",
                                "stalling-receiver-" + UUID.randomUUID().toString().substring(0, 8), "url", url,
                                "secret", "whsec-stalled-body-test-value", "timeoutMs", ENDPOINT_TIMEOUT_MS))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private void createRule(String endpointId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        Map.of("name", "missing-rate-" + UUID.randomUUID().toString().substring(0, 8), "type",
                                "USAGE_MISSING_RATE", "threshold", 0.5, "webhookEndpointId", endpointId))))
                .andExpect(status().isOk());
    }

    private Map<String, Object> onlyAttempt(String endpointId) {
        List<Map<String, Object>> rows = attemptRows(endpointId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> attemptRows(String endpointId) {
        return jdbc.query(
                "SELECT attempt, http_status, next_retry_at, error_message FROM webhook_delivery_attempts "
                        + "WHERE endpoint_id = :endpointId ORDER BY attempt",
                new MapSqlParameterSource("endpointId", UUID.fromString(endpointId)), (rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("attempt", rs.getInt("attempt"));
                    row.put("http_status", rs.getObject("http_status"));
                    row.put("next_retry_at", rs.getTimestamp("next_retry_at"));
                    row.put("error_message", rs.getString("error_message"));
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
