package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate-signal alerts (ADR-0026 option D, issue #706): the two control-plane
 * signals that make the ADR's §4 trigger conditions answerable — the upstream
 * 429 COUNT, and the busiest key's request count — with zero request-path work
 * and no high-cardinality metric labels (the key dimension lives in the SQL
 * aggregate, per ADR-0026 §6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Rate-signal alerts: upstream 429 count + busiest-key rate (ADR-0026 D, #706)")
class RateSignalAlertIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    /** The migration-seeded tenant (V1). */
    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // Drive evaluation explicitly; the scheduler stays out of the way.
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

    private UUID seededProjectId;
    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper
                                .writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(), "root", "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        reset();
    }

    // ------------------------------------------------------------------
    // UPSTREAM_RATE_LIMITED: a COUNT of upstream 429s, and only those
    // ------------------------------------------------------------------

    @Test
    @DisplayName("counts upstream 429s only — 5xx and the gateway's own quota 429s are not throttling")
    void countsUpstream429Only() throws Exception {
        String rule = createRule("UPSTREAM_RATE_LIMITED", 2.0);
        seedEvent(429);
        seedEvent(429);
        seedEvent(500); // upstream failure, a different incident — UPSTREAM_ERROR_RATE's job
        seedEvent(200);
        seedEvent(null); // gateway-rejected quota request: never reached upstream

        alertEvaluator.evaluateAll();

        assertThat(countEvents(rule)).isEqualTo(1L);
        assertThat(eventValue(rule)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("stays quiet below the threshold, and fires once per hour bucket")
    void thresholdAndDedupeHold() throws Exception {
        String strict = createRule("UPSTREAM_RATE_LIMITED", 3.0);
        seedEvent(429);
        seedEvent(429);

        alertEvaluator.evaluateAll();
        assertThat(countEvents(strict)).isZero();

        // Same hour, same rule: still quiet, and a second sweep adds nothing.
        alertEvaluator.evaluateAll();
        assertThat(countEvents(strict)).isZero();

        String met = createRule("UPSTREAM_RATE_LIMITED", 2.0);
        alertEvaluator.evaluateAll();
        assertThat(countEvents(met)).isEqualTo(1L);
        alertEvaluator.evaluateAll();
        assertThat(countEvents(met)).as("one event per (rule, hour)").isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // KEY_REQUEST_RATE: the busiest key, with attribution in the payload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reports the busiest key's count and names it in the event payload")
    void busiestKeyIsAttributed() throws Exception {
        String rule = createRule("KEY_REQUEST_RATE", 3.0);
        UUID hot = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        seedEventFor(hot, 200);
        seedEventFor(hot, 200);
        seedEventFor(hot, 429);
        seedEventFor(quiet, 200);

        alertEvaluator.evaluateAll();

        assertThat(countEvents(rule)).isEqualTo(1L);
        assertThat(eventValue(rule)).isEqualByComparingTo("3");
        String payloadJson = jdbc.queryForObject("SELECT payload_json::text FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(rule)), String.class);
        // Parse, do not substring-match: jsonb's text form inserts spaces, and a
        // structural assertion is what "attributed to the right key" actually means.
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
        assertThat(payload).containsEntry("keyId", hot.toString()).containsEntry("requests", 3);
        assertThat(payload).as("the quiet key is not the subject").doesNotContainValue(quiet.toString());
    }

    @Test
    @DisplayName("a peak below the threshold stays quiet")
    void belowThresholdStaysQuiet() throws Exception {
        String strict = createRule("KEY_REQUEST_RATE", 4.0);
        UUID key = UUID.randomUUID();
        seedEventFor(key, 200);
        seedEventFor(key, 200);
        seedEventFor(key, 200);

        alertEvaluator.evaluateAll();

        assertThat(countEvents(strict)).isZero();
    }

    // ------------------------------------------------------------------

    private String createRule(String type, double threshold) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "rate-signal-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("type", type);
        body.put("threshold", threshold);
        body.put("dedupeMinutes", 60);
        MvcResult result = mockMvc.perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    /**
     * One usage fact; {@code upstreamStatus} null models a gateway-rejected
     * request.
     */
    /**
     * The fact table needs a project; reset() clears it, so create one on demand.
     */
    private UUID projectId() {
        if (seededProjectId == null) {
            seededProjectId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:id, :tenantId, 'RATE', 'Rate Signal', 'ACTIVE', 'rate-signal', 0)
                    """, new MapSqlParameterSource("id", seededProjectId).addValue("tenantId", SEED_TENANT));
        }
        return seededProjectId;
    }

    private void seedEvent(Integer upstreamStatus) {
        seedEventFor(UUID.randomUUID(), upstreamStatus);
    }

    private void seedEventFor(UUID keyId, Integer upstreamStatus) {
        UUID projectId = projectId();
        UUID productId = jdbc.queryForObject("SELECT id FROM provider_products ORDER BY display_name LIMIT 1",
                new MapSqlParameterSource(), UUID.class);
        jdbc.update("""
                INSERT INTO usage_event
                    (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                     model_id, cache_level, is_complete, gateway_request_id, upstream_status_code, occurred_at)
                VALUES (:id, :tenantId, :requestId, :keyId, :projectId, :productId, 'rate-signal-model',
                        'UPSTREAM', TRUE, :gatewayId, :upstreamStatus, now())
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", SEED_TENANT)
                .addValue("requestId", UUID.randomUUID().toString()).addValue("keyId", keyId)
                .addValue("projectId", projectId).addValue("productId", productId)
                .addValue("gatewayId", UUID.randomUUID().toString()).addValue("upstreamStatus", upstreamStatus));
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

    private static Cookie cookie(MvcResult result, String name) {
        if (result.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie c : result.getResponse().getCookies()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private void reset() {
        seededProjectId = null;
        // Child-first for the canonical migration set: bootstrap writes users/sessions
        // and
        // the audit chain, so a second test could not bootstrap without clearing them.
        for (String table : new String[]{"webhook_delivery_attempts", "alert_events", "alert_rules",
                "webhook_endpoints", "gateway_queue_signal", "usage_event", "admin_audit_events", "user_sessions",
                "users", "projects"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // FK ordering across bootstrap state; the next test re-seeds.
            }
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
