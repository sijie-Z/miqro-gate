package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit coverage batch 2 (issue #324): alert rules, webhook endpoints, budgets,
 * global config and manual model catalog rows are audited on both the session
 * surface (actor = session admin) and the open admin API (actor = issuing
 * admin, summary carries the via key marker). Summaries never carry the webhook
 * secret or config values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Audit coverage batch 2 integration tests (PostgreSQL)")
class AdminAuditCoverageBatch2IntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String WEBHOOK_SECRET = "whsec-audit-secret-marker";
    private static final String CONFIG_VALUE = "CONFIG-VALUE-SECRET-MARKER";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        // The webhook fixture uses a loopback URL (creation-time SSRF gate).
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID adminUserId;
    private final String adminUsername = "aud2_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
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
        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername), UUID.class);
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : List.of("admin_api_keys", "webhook_delivery_attempts", "alert_events", "alert_rules",
                "webhook_endpoints", "model_catalog", "config_entries", "budget", "projects", "usage_event",
                "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc
                .perform(builder.cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .header("X-Request-Id", "req-" + UUID.randomUUID()))
                .andExpect(status().is2xxSuccessful()).andReturn();
    }

    private String postJson(String path, String json) throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).getResponse()
                .getContentAsString();
    }

    private String putJson(String path, String json) throws Exception {
        return call(put(path).contentType(MediaType.APPLICATION_JSON).content(json)).getResponse().getContentAsString();
    }

    private long countEvents(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    private Map<String, Object> latestEvent(String action) {
        return jdbc.queryForMap("""
                SELECT actor_id, change_summary::text AS summary, admin_request_id
                FROM admin_audit_events WHERE action = :action ORDER BY chain_position DESC LIMIT 1
                """, new MapSqlParameterSource("action", action));
    }

    private void assertAudited(String action, UUID expectedActor) throws Exception {
        assertThat(countEvents(action)).as("event %s", action).isEqualTo(1);
        Map<String, Object> event = latestEvent(action);
        assertThat(event.get("actor_id")).isEqualTo(expectedActor);
        String summary = (String) event.get("summary");
        objectMapper.readTree(summary);
        assertThat(event.get("admin_request_id")).isNotNull();
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

    @Test
    @DisplayName("session surface: alert rules, webhooks, budget, config and manual catalog audited")
    void sessionSurfaceAudited() throws Exception {
        // Alert rules: create / update / delete.
        String rule = postJson("/api/v1/admin/alert-rules",
                "{\"name\":\"audit-rule\",\"type\":\"USAGE_SURGE\",\"threshold\":100,\"dedupeMinutes\":60}");
        assertAudited("ALERT_RULE_CREATE", adminUserId);
        assertThat((String) latestEvent("ALERT_RULE_CREATE").get("summary")).contains("audit-rule")
                .contains("USAGE_SURGE");
        String ruleId = objectMapper.readTree(rule).get("id").asText();
        call(patch("/api/v1/admin/alert-rules/" + ruleId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"));
        assertAudited("ALERT_RULE_UPDATE", adminUserId);
        call(delete("/api/v1/admin/alert-rules/" + ruleId));
        assertAudited("ALERT_RULE_DELETE", adminUserId);

        // Webhooks: create (secret never in the summary) / update / delete.
        String hook = postJson("/api/v1/admin/webhooks",
                "{\"name\":\"audit-hook\",\"url\":\"http://127.0.0.1:18089/hook\",\"secret\":\"" + WEBHOOK_SECRET
                        + "\"}");
        assertAudited("WEBHOOK_CREATE", adminUserId);
        String hookSummary = (String) latestEvent("WEBHOOK_CREATE").get("summary");
        assertThat(hookSummary).contains("audit-hook").contains("127.0.0.1").doesNotContain(WEBHOOK_SECRET);
        String hookId = objectMapper.readTree(hook).get("id").asText();
        call(patch("/api/v1/admin/webhooks/" + hookId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"));
        assertAudited("WEBHOOK_UPDATE", adminUserId);
        call(delete("/api/v1/admin/webhooks/" + hookId));
        assertAudited("WEBHOOK_DELETE", adminUserId);

        // Budgets: put / delete (project seeded over the API).
        String project = postJson("/api/v1/admin/projects", "{\"code\":\"audit-b2\",\"name\":\"Audit B2\"}");
        String projectId = objectMapper.readTree(project).get("id").asText();
        putJson("/api/v1/admin/projects/" + projectId + "/budget",
                "{\"month\":\"2026-09\",\"amount\":100.50,\"currency\":\"CNY\",\"alertThresholdPct\":80}");
        assertAudited("BUDGET_PUT", adminUserId);
        call(delete("/api/v1/admin/projects/" + projectId + "/budget").param("month", "2026-09"));
        assertAudited("BUDGET_DELETE", adminUserId);

        // Global config: value never in the summary.
        putJson("/api/v1/admin/configs", "{\"group\":\"ui\",\"key\":\"banner\",\"value\":\"" + CONFIG_VALUE + "\"}");
        assertAudited("CONFIG_PUT", adminUserId);
        assertThat((String) latestEvent("CONFIG_PUT").get("summary")).contains("ui").contains("banner")
                .doesNotContain(CONFIG_VALUE);
        call(delete("/api/v1/admin/configs/ui/banner"));
        assertAudited("CONFIG_DELETE", adminUserId);

        // Manual model catalog: add / remove.
        UUID productId = jdbc.queryForObject("SELECT id FROM provider_products ORDER BY display_name LIMIT 1",
                new MapSqlParameterSource(), UUID.class);
        String row = postJson("/api/v1/admin/models", "{\"providerProductId\":\"" + productId
                + "\",\"modelId\":\"it-manual-audit-1\",\"displayName\":\"IT Manual\"}");
        assertAudited("MODEL_CATALOG_ADD_MANUAL", adminUserId);
        String rowId = objectMapper.readTree(row).get("id").asText();
        call(delete("/api/v1/admin/models/" + rowId));
        assertAudited("MODEL_CATALOG_DELETE_MANUAL", adminUserId);
    }

    @Test
    @DisplayName("machine surface: actor is the issuing admin and the summary carries the via marker")
    void machineSurfaceAudited() throws Exception {
        String machineToken = "mqk_admin_audit_b2_token";
        jdbc.update("""
                INSERT INTO admin_api_keys (id, tenant_id, name, key_digest, key_prefix, created_by, created_at)
                VALUES (:id, :tenantId, 'audit-b2-key', :digest, 'mqk_adm_b2', :createdBy, now())
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT_ID)
                .addValue("digest", sha256(machineToken)).addValue("createdBy", adminUserId));

        MvcResult created = mockMvc
                .perform(post("/api/v1/admin-api/alert-rules").header("Authorization", "Bearer " + machineToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"machine-rule\",\"type\":\"USAGE_SURGE\",\"threshold\":50}"))
                .andExpect(status().isCreated()).andReturn();
        assertAudited("ALERT_RULE_CREATE", adminUserId);
        Map<String, Object> event = latestEvent("ALERT_RULE_CREATE");
        assertThat((String) event.get("summary")).contains("machine-rule").contains("via")
                .contains("admin-api:audit-b2-key");
        String ruleId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(
                delete("/api/v1/admin-api/alert-rules/" + ruleId).header("Authorization", "Bearer " + machineToken))
                .andExpect(status().isNoContent());
        assertAudited("ALERT_RULE_DELETE", adminUserId);
        assertThat((String) latestEvent("ALERT_RULE_DELETE").get("summary")).contains("admin-api:audit-b2-key");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
