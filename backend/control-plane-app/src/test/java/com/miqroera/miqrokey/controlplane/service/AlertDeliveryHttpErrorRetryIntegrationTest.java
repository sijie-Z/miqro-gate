package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delivery outcome classification for alert webhooks against real PostgreSQL:
 * a receiver that answers with an HTTP error status has <em>not</em> accepted
 * the alert, so the attempt must be recorded as a failure ("failed" not
 * "delivered") and a transient (5xx) receiver must be armed for the documented
 * exponential-backoff retry ({@code docs/api-contract.md}: "投递失败指数退避重试最多 3 次").
 *
 * <p>
 * The retry <em>bound</em> itself is out of scope here: the sweep's stale-row
 * behaviour is tracked separately (#911 / #916).
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Alert delivery HTTP-error retry integration tests (PostgreSQL)")
class AlertDeliveryHttpErrorRetryIntegrationTest {

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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private HttpServer mockReceiver;
    private String mockBaseUrl;
    private final AtomicInteger received = new AtomicInteger();
    /** Status the mock receiver answers with; set per test. */
    private final AtomicInteger receiverStatus = new AtomicInteger(200);
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
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
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(receiverStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    @DisplayName("a 5xx receiver arms the documented backoff retry instead of being recorded as delivered")
    void serverErrorArmsBackoffRetry() throws Exception {
        receiverStatus.set(500);
        String endpointId = createEndpoint();
        createRule(endpointId);
        fx.insertUsage(true);
        fx.insertUsage(false);

        alertEvaluator.evaluateAll();

        // The receiver was called and answered 500 — the alert was not accepted.
        assertThat(received.get()).isEqualTo(1);
        Map<String, Object> row = onlyAttempt(endpointId);
        assertThat(row.get("http_status")).isEqualTo(500);
        assertThat(row.get("next_retry_at")).as("an HTTP 5xx delivery must be armed for a backoff retry").isNotNull();
        assertThat(row.get("error_message")).as("a failed delivery must record a scrubbed reason").isNotNull();
    }

    @Test
    @DisplayName("the armed retry reaches the receiver again on the next sweep")
    void serverErrorIsRetriedByTheSweep() throws Exception {
        receiverStatus.set(500);
        String endpointId = createEndpoint();
        createRule(endpointId);
        fx.insertUsage(true);
        fx.insertUsage(false);

        alertEvaluator.evaluateAll();
        assertThat(received.get()).isEqualTo(1);

        // The first backoff is 2^1 × 60s in the future; pull it into the past so a
        // single sweep sees the delivery as due (the sweep is driven explicitly, as
        // the scheduled evaluator is slowed down for the test).
        int backdated = jdbc.update(
                "UPDATE webhook_delivery_attempts SET next_retry_at = now() - interval '1 minute' "
                        + "WHERE endpoint_id = :endpointId",
                new MapSqlParameterSource("endpointId", UUID.fromString(endpointId)));
        assertThat(backdated).as("the failed attempt must have a backoff deadline to pull back").isEqualTo(1);

        dispatcher.retryDue();

        assertThat(received.get()).as("the failed delivery must be retried").isEqualTo(2);
    }

    @Test
    @DisplayName("a 4xx receiver is recorded as failed but not retried (client error is terminal)")
    void clientErrorIsRecordedButNotRetried() throws Exception {
        receiverStatus.set(404);
        String endpointId = createEndpoint();
        createRule(endpointId);
        fx.insertUsage(true);
        fx.insertUsage(false);

        alertEvaluator.evaluateAll();

        assertThat(received.get()).isEqualTo(1);
        Map<String, Object> row = onlyAttempt(endpointId);
        assertThat(row.get("http_status")).isEqualTo(404);
        assertThat(row.get("error_message")).as("a 4xx is a failed delivery, not a delivered one").isNotNull();
        // Terminal: retrying a request the receiver rejects cannot succeed, so no
        // backoff deadline is armed (mirrors the F12 SERVER_5XX retry vocabulary).
        assertThat(row.get("next_retry_at")).isNull();

        dispatcher.retryDue();
        assertThat(received.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String createEndpoint() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name",
                                "receiver-" + UUID.randomUUID().toString().substring(0, 8), "url", mockBaseUrl,
                                "secret", "whsec-retry-test-value"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private void createRule(String endpointId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name",
                        "missing-rate-" + UUID.randomUUID().toString().substring(0, 8), "type", "USAGE_MISSING_RATE",
                        "threshold", 0.5, "webhookEndpointId", endpointId))))
                .andExpect(status().isOk());
    }

    private Map<String, Object> onlyAttempt(String endpointId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT attempt, http_status, next_retry_at, error_message FROM webhook_delivery_attempts "
                        + "WHERE endpoint_id = :endpointId ORDER BY attempt",
                new MapSqlParameterSource("endpointId", UUID.fromString(endpointId)),
                (rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("attempt", rs.getInt("attempt"));
                    row.put("http_status", rs.getObject("http_status"));
                    row.put("next_retry_at", rs.getTimestamp("next_retry_at"));
                    row.put("error_message", rs.getString("error_message"));
                    return row;
                });
        assertThat(rows).hasSize(1);
        return rows.get(0);
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
