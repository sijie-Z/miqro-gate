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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usage-queue saturation alerting against real PostgreSQL (F07, #245): the
 * gateway writes {@code gateway_queue_signal} facts and the control plane
 * evaluates them through the ordinary {@link AlertEvaluator} →
 * {@link AlertEventDispatcher} path.
 *
 * <p>
 * The gateway writes the process-wide signal under the platform (seed) tenant,
 * so the two properties worth pinning down are the tenant scope of the metric
 * and the shape of the fired event.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Usage-queue saturation alert integration tests (PostgreSQL)")
class UsageQueueSaturationAlertIntegrationTest {

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

    /** The migration-seeded platform tenant; the gateway reports under this one. */
    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** A second tenant that owns no gateway, so no drop fact is ever written for it. */
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-00000000f017");
    private static final String OTHER_TENANT_CODE = "tenant-b";

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
    private final List<String> signatures = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        reset();
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
        reset();
    }

    // ------------------------------------------------------------------
    // 1. the wiring: fact row -> evaluated -> one event -> one signed delivery
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a drop fact fires one event, delivers it signed, and deduplicates within the hour")
    void dropFactFiresOnceAndDeliversSigned() throws Exception {
        String endpointId = createEndpoint();
        String ruleId = createRule("USAGE_QUEUE_SATURATION", 1.0, endpointId);
        insertSignal(SEED_TENANT, 7, Instant.now());

        alertEvaluator.evaluateAll();

        assertThat(countEvents(ruleId)).isEqualTo(1L);
        // The event carries the metric itself: the number of lost usage events.
        assertThat(eventValue(ruleId)).isEqualByComparingTo("7");
        assertThat(received.get()).isEqualTo(1);
        assertThat(signatures.get(0)).startsWith("sha256=");
        assertThat(countDeliveryAttempts()).isEqualTo(1L);

        // Same hour bucket: deduplicated, no second delivery.
        alertEvaluator.evaluateAll();
        assertThat(countEvents(ruleId)).isEqualTo(1L);
        assertThat(received.get()).isEqualTo(1);
        assertThat(countDeliveryAttempts()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the threshold compares against the dropped-event count, not a ratio")
    void thresholdIsACountBoundary() throws Exception {
        String ruleId = createRule("USAGE_QUEUE_SATURATION", 3.0, null);

        insertSignal(SEED_TENANT, 2, Instant.now());
        alertEvaluator.evaluateAll();
        assertThat(countEvents(ruleId)).isZero();

        insertSignal(SEED_TENANT, 1, Instant.now());
        alertEvaluator.evaluateAll();
        assertThat(countEvents(ruleId)).isEqualTo(1L);
        assertThat(eventValue(ruleId)).isEqualByComparingTo("3");
    }

    // ------------------------------------------------------------------
    // 2. tenant scope: the platform signal must not leak across tenants
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the metric only counts the rule tenant's drop facts")
    void metricIsScopedToTheRuleTenant() {
        insertSignal(SEED_TENANT, 5, Instant.now());

        assertThat(alertEvaluator.metric("USAGE_QUEUE_SATURATION", SEED_TENANT, null)).isEqualByComparingTo("5");
        // Another tenant's rule sees an empty window, never the platform aggregate.
        assertThat(alertEvaluator.metric("USAGE_QUEUE_SATURATION", OTHER_TENANT, null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the pre-existing metrics are tenant-scoped too (no cross-tenant aggregate)")
    void periodicMetricsAreScopedToTheRuleTenant() {
        insertUsage(true);
        insertUsage(false);

        assertThat(alertEvaluator.metric("USAGE_MISSING_RATE", SEED_TENANT, null)).isEqualByComparingTo("0.5");
        // No rows of its own -> no data -> the rule cannot fire on someone else's.
        assertThat(alertEvaluator.metric("USAGE_MISSING_RATE", OTHER_TENANT, null)).isNull();
        assertThat(alertEvaluator.metric("USAGE_SURGE", OTHER_TENANT, null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a non-seed tenant's rule never fires on the platform's drop facts")
    void otherTenantRuleNeverFires() throws Exception {
        createOtherTenant();
        UUID otherRule = insertRuleFor(OTHER_TENANT, 1.0);
        // Positive control: an identical rule owned by the platform tenant does fire.
        String seedRule = createRule("USAGE_QUEUE_SATURATION", 1.0, null);

        insertSignal(SEED_TENANT, 9, Instant.now());
        alertEvaluator.evaluateAll();

        assertThat(countEvents(seedRule)).isEqualTo(1L);
        assertThat(countEvents(otherRule.toString())).isZero();
        Long otherTenantEvents = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", OTHER_TENANT), Long.class);
        assertThat(otherTenantEvents).isZero();
    }

    // ------------------------------------------------------------------
    // 3. boundaries
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty window does not fire")
    void emptyWindowDoesNotFire() throws Exception {
        String ruleId = createRule("USAGE_QUEUE_SATURATION", 1.0, null);

        alertEvaluator.evaluateAll();

        assertThat(alertEvaluator.metric("USAGE_QUEUE_SATURATION", SEED_TENANT, null)).isEqualByComparingTo("0");
        assertThat(countEvents(ruleId)).isZero();
        assertThat(received.get()).isZero();

        // A zero-drop row is not merely absent, it is unrepresentable: the schema
        // rejects it, so "no drop fact" is the only way a lost nothing looks.
        assertThatThrownBy(() -> insertSignal(SEED_TENANT, 0, Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("drop facts older than the one-hour window are ignored")
    void staleDropFactsAreIgnored() throws Exception {
        String ruleId = createRule("USAGE_QUEUE_SATURATION", 1.0, null);
        insertSignal(SEED_TENANT, 50, Instant.now().minusSeconds(2 * 3600));

        alertEvaluator.evaluateAll();

        assertThat(alertEvaluator.metric("USAGE_QUEUE_SATURATION", SEED_TENANT, null)).isEqualByComparingTo("0");
        assertThat(countEvents(ruleId)).isZero();
    }

    @Test
    @DisplayName("a rule without a webhook endpoint records the event and delivers nothing")
    void ruleWithoutEndpointRecordsOnly() throws Exception {
        String ruleId = createRule("USAGE_QUEUE_SATURATION", 1.0, null);
        insertSignal(SEED_TENANT, 4, Instant.now());

        alertEvaluator.evaluateAll();

        assertThat(countEvents(ruleId)).isEqualTo(1L);
        assertThat(countDeliveryAttempts()).isZero();
        assertThat(received.get()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void handleHook(HttpExchange exchange) throws java.io.IOException {
        received.incrementAndGet();
        String signature = exchange.getRequestHeaders().getFirst("X-MiQroKey-Signature");
        if (signature != null) {
            signatures.add(signature);
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String createEndpoint() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "main", "url", mockBaseUrl, "secret", "whsec-test-value"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    /** Creates a rule for the authenticated (platform) tenant through the public API. */
    private String createRule(String type, double threshold, String endpointId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "queue-saturation-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("type", type);
        body.put("threshold", threshold);
        body.put("dedupeMinutes", 60);
        if (endpointId != null) {
            body.put("webhookEndpointId", endpointId);
        }
        MvcResult result = mockMvc
                .perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private void createOtherTenant() {
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version)
                VALUES (:id, :code, 'Tenant B', 'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """, new MapSqlParameterSource("id", OTHER_TENANT).addValue("code", OTHER_TENANT_CODE));
    }

    /** A rule the API cannot create for us: no session exists for a foreign tenant. */
    private UUID insertRuleFor(UUID tenantId, double threshold) {
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alert_rules
                    (id, tenant_id, name, type, threshold, dedupe_minutes, enabled, version)
                VALUES (:id, :tenantId, 'foreign-queue-saturation', 'USAGE_QUEUE_SATURATION', :threshold, 60, TRUE, 0)
                """, new MapSqlParameterSource("id", ruleId).addValue("tenantId", tenantId).addValue("threshold",
                BigDecimal.valueOf(threshold)));
        return ruleId;
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

    private long countEvents(String ruleId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)), Long.class);
        return count != null ? count : 0L;
    }

    private BigDecimal eventValue(String ruleId) {
        return jdbc.queryForObject("SELECT value FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)), BigDecimal.class);
    }

    private long countDeliveryAttempts() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery_attempts",
                new MapSqlParameterSource(), Long.class);
        return count != null ? count : 0L;
    }

    private void reset() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "gateway_queue_signal", "usage_event", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering above is child-first for the canonical migration set.
            }
        }
        // The seed tenant is migration-owned; only the test's extra tenant goes away.
        jdbc.update("DELETE FROM tenants WHERE code = :code", new MapSqlParameterSource("code", OTHER_TENANT_CODE));
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie c : r.getResponse().getCookies()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
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
