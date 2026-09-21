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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-rule isolation of the alert evaluation cycle (#1168): one rule whose
 * evaluation throws must not abort the cycle for every other rule, and must not
 * postpone due delivery retries.
 *
 * <p>
 * The poison is a real evaluation failure, not a mock: a
 * {@code USAGE_QUEUE_SATURATION} rule whose metric (a 10⁹-drop signal row)
 * overflows {@code alert_events.value numeric(12,6)}, so its event write — one
 * step of every {@code evaluate(rule)} — throws deterministically ("numeric
 * field overflow"). {@code metric()} itself cannot be made to throw for any
 * rule row: its aggregates always return exactly one row, and both scope
 * parsers swallow their own parse errors (seed
 * {@code malformedScopeIsSwallowedByTheScopeParsers} pins that down). An
 * earlier plan to poison via malformed {@code scope_json} on a BUDGET/QUOTA
 * rule is therefore void.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Alert evaluation isolation integration tests (#1168, PostgreSQL)")
class AlertEvaluationIsolationIntegrationTest {

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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private HttpServer mockReceiver;
    private String mockBaseUrl;
    private final AtomicInteger received = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        reset();
        // A receiver that rejects every delivery (HTTP 500): the retry-arming
        // recipe copied from AlertDeliveryHttpErrorRetryIntegrationTest.
        mockReceiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mockReceiver.createContext("/hook", this::handleHookRejecting);
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
        reset();
    }

    // ------------------------------------------------------------------
    // the #1168 invariant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a sick rule must not postpone due delivery retries — the cycle still reaches retryDue")
    void dueRetryIsNotSkippedWhenARuleThrows() throws Exception {
        String endpointId = createEndpoint();
        String ruleId = createRule("USAGE_MISSING_RATE", 0.5, endpointId);
        insertUsage(true);
        insertUsage(false);

        // Arm the backoff retry: the rule fires, the 500 receiver fails the first
        // delivery and the attempt is armed with a deadline.
        alertEvaluator.evaluateAll();
        assertThat(received.get()).isEqualTo(1);
        UUID armedEventId = onlyEventOf(ruleId);
        backdateRetry(endpointId);

        // From now on one rule's evaluation throws every cycle.
        insertPoisonRule();

        // The next cycle must still sweep the due retry. The cursor is the armed
        // event itself (attempt numbering is per (event, endpoint)): no matter
        // where the sick rule sits in the evaluation order, a single
        // evaluateAll() must advance this very attempt to attempt 2. An earlier
        // healthy re-fire in the same call cannot fake it — that would only add
        // an attempt 1 of a different event.
        alertEvaluator.evaluateAll();

        assertThat(attemptCount(armedEventId, 2))
                .as("the due retry must have run in this cycle even though another rule threw").isEqualTo(1L);
        assertThat(received.get()).as("the retry actually reached the receiver").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a sick rule must not silence the rest of the cycle — later rules are still evaluated")
    void laterRulesAreStillEvaluatedWhenARuleThrows() throws Exception {
        // Poison first: rules are evaluated in the order the query returns them
        // (insertion order for this freshly repopulated table), so an evaluation
        // that aborts on the sick rule never reaches the healthy one behind it.
        insertPoisonRule();
        String healthyRuleId = createRule("USAGE_MISSING_RATE", 0.5, null);
        insertUsage(true);
        insertUsage(false);

        alertEvaluator.evaluateAll();

        assertThat(countEvents(healthyRuleId)).as("the healthy rule must still be evaluated when another rule threw")
                .isEqualTo(1L);
    }

    /**
     * Calibration of the injection point, not of the fix: the issue's candidate
     * poison (malformed {@code scope_json} on a BUDGET/QUOTA rule) cannot throw —
     * {@code budgetWatermark()} and {@code quotaWatermark()} each catch every
     * exception and return null ("malformed scope … nothing to alert"). Pinned here
     * so the poison above is provably a real failure, and so a future change to
     * that swallow behaviour is noticed.
     */
    @Test
    @DisplayName("calibration: a malformed scope_json is swallowed by the scope parsers, never thrown")
    void malformedScopeIsSwallowedByTheScopeParsers() {
        assertThat(alertEvaluator.metric("BUDGET_THRESHOLD", SEED_TENANT, "not-json")).isNull();
        assertThat(alertEvaluator.metric("QUOTA_THRESHOLD", SEED_TENANT, "not-json")).isNull();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The migration-seeded platform tenant. */
    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private void handleHookRejecting(HttpExchange exchange) throws java.io.IOException {
        received.incrementAndGet();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(500, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String createEndpoint() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "rejecting-" + UUID.randomUUID().toString().substring(0, 8), "url",
                                        mockBaseUrl, "secret", "whsec-isolation-test-value"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private String createRule(String type, double threshold, String endpointId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "isolation-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("type", type);
        body.put("threshold", threshold);
        body.put("dedupeMinutes", 60);
        if (endpointId != null) {
            body.put("webhookEndpointId", endpointId);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    /**
     * The calibrated poison (#1168): its metric (10⁹ lost events) is real, but the
     * resulting {@code alert_events.value} overflows {@code numeric(12,6)} — "A
     * field with precision 12, scale 6 must round to an absolute value less than
     * 10^6" — so every {@code evaluate(rule)} for this rule throws at the event
     * write.
     */
    private void insertPoisonRule() {
        insertSignal(SEED_TENANT, 1_000_000_000L, Instant.now());
        jdbc.update("""
                INSERT INTO alert_rules
                    (id, tenant_id, name, type, threshold, dedupe_minutes, enabled, version)
                VALUES (:id, :tenantId, 'poison-queue-saturation', 'USAGE_QUEUE_SATURATION', 1, 60, TRUE, 0)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", SEED_TENANT));
    }

    /**
     * Pulls the armed backoff deadline into the past so one sweep sees it as due.
     */
    private void backdateRetry(String endpointId) {
        int backdated = jdbc.update(
                "UPDATE webhook_delivery_attempts SET next_retry_at = now() - interval '1 minute' "
                        + "WHERE endpoint_id = :endpointId",
                new MapSqlParameterSource("endpointId", UUID.fromString(endpointId)));
        assertThat(backdated).as("the failed attempt must have a backoff deadline to pull back").isEqualTo(1);
    }

    private UUID onlyEventOf(String ruleId) {
        List<UUID> ids = jdbc.query("SELECT id FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)),
                (rs, rowNum) -> (UUID) rs.getObject("id"));
        assertThat(ids).as("the arming cycle must have recorded exactly one event").hasSize(1);
        return ids.get(0);
    }

    private long attemptCount(UUID eventId, int attempt) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_attempts WHERE event_id = :eventId AND attempt = :attempt",
                new MapSqlParameterSource("eventId", eventId).addValue("attempt", attempt), Long.class);
        return count != null ? count : 0L;
    }

    private long countEvents(String ruleId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)), Long.class);
        return count != null ? count : 0L;
    }

    private void insertSignal(UUID tenantId, long dropped, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO gateway_queue_signal
                    (id, tenant_id, occurred_at, dropped, queued_high_water, capacity, saturation_mode)
                VALUES (:id, :tenantId, :occurredAt, :dropped, 512, 512, 'DROP')
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                .addValue("occurredAt", Timestamp.from(occurredAt)).addValue("dropped", dropped));
    }

    private void insertUsage(boolean usageMissing) {
        jdbc.update("""
                INSERT INTO usage_event
                    (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                     model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                     upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, 'req-' || :id, '00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
                        'model-a', 'UPSTREAM', 10, 5, 15, 42, 200, TRUE, :usageMissing, 'greq', now())
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", SEED_TENANT)
                .addValue("usageMissing", usageMissing));
    }

    private void reset() {
        // Child-first, same list as the sibling alert integration tests: the
        // container is shared, so only this domain's rows are removed.
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "gateway_queue_signal", "usage_event", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering above is child-first for the canonical migration set.
            }
        }
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
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
