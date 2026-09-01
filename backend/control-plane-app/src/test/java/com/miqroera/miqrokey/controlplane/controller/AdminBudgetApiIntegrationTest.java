package com.miqroera.miqrokey.controlplane.controller;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.service.AlertEvaluator;

import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Monthly per-project budgets (G8.2): plan CRUD, month/threshold validation and
 * the live spend watermark derived from usage + price snapshots.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin budget API integration tests (PostgreSQL)")
class AdminBudgetApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    /** Seed tenant of the single-tenant deployment (bootstrap creates it). */
    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
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
    private String month;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), "root", "Admin"))))
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
        month = YearMonth.now().toString();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : new String[]{"webhook_delivery_attempts", "alert_events", "alert_rules", "budget",
                "usage_event", "price_snapshot", "virtual_keys", "project_provider_grant_models",
                "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "quota_snapshots",
                "upstream_subscriptions", "projects", "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("budget endpoints require authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/budgets")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("budget lifecycle: create, upsert, list, delete")
    void budgetLifecycle() throws Exception {
        UUID projectId = seedProject("BUD", "Budget Project");

        // Create.
        mockMvc.perform(put("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content("""
                        {"month":"%s","amount":100,"currency":"CNY","alertThresholdPct":50}
                        """.formatted(month))).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectCode").value("BUD")).andExpect(jsonPath("$.amount").value(100))
                .andExpect(jsonPath("$.currency").value("CNY")).andExpect(jsonPath("$.alertThresholdPct").value(50))
                .andExpect(jsonPath("$.spent").value(0)).andExpect(jsonPath("$.spentPct").value(0))
                .andExpect(jsonPath("$.level").value("NORMAL"));

        // Read single + list.
        mockMvc.perform(get("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(100));
        mockMvc.perform(get("/api/v1/admin/budgets").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Upsert in place.
        mockMvc.perform(put("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"" + month + "\",\"amount\":200,\"alertThresholdPct\":80}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(200))
                .andExpect(jsonPath("$.alertThresholdPct").value(80));
        mockMvc.perform(get("/api/v1/admin/budgets").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Delete, then gone.
        mockMvc.perform(delete("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUDGET_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("budget validation: month format, amount, threshold, project scope")
    void budgetValidation() throws Exception {
        UUID projectId = seedProject("BUD", "Budget Project");

        mockMvc.perform(put("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"2026-13\",\"amount\":100}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"" + month + "\",\"amount\":0}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"" + month + "\",\"amount\":100,\"alertThresholdPct\":101}"))
                .andExpect(status().isBadRequest());

        // Unknown project -> 404 (tenant-scoped).
        mockMvc.perform(put("/api/v1/admin/projects/" + UUID.randomUUID() + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"" + month + "\",\"amount\":100}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        // Invalid month on read paths.
        mockMvc.perform(get("/api/v1/admin/budgets?month=bad").cookie(sessionCookie))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("spend watermark reflects month usage and drives the alert level")
    void spendWatermark() throws Exception {
        UUID projectId = seedProject("BUD", "Budget Project");
        UUID quietId = seedProject("QET", "Quiet Project");
        seedUsage(projectId, "budget-model", 1000L, 1000L);

        // 1000 input + 1000 output tokens at ¥2/1M + ¥8/1M = ¥0.01; budget ¥0.01 ->
        // EXCEEDED.
        mockMvc.perform(put("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"" + month + "\",\"amount\":0.01,\"alertThresholdPct\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.spent").value(0.01))
                .andExpect(jsonPath("$.spentPct").value(100)).andExpect(jsonPath("$.level").value("EXCEEDED"));

        // The quiet project has no usage: NORMAL at any threshold.
        mockMvc.perform(put("/api/v1/admin/projects/" + quietId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"" + month + "\",\"amount\":10,\"alertThresholdPct\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.spent").value(0))
                .andExpect(jsonPath("$.spentPct").value(0)).andExpect(jsonPath("$.level").value("NORMAL"));

        // Monthly list shows both with correct levels.
        mockMvc.perform(get("/api/v1/admin/budgets").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("BUDGET_THRESHOLD rules fire once per month when the watermark crosses the threshold")
    void budgetAlertFiresOncePerMonth() throws Exception {
        UUID projectId = seedProject("BUD", "Budget Project");
        seedUsage(projectId, "budget-model", 1000L, 1000L);
        mockMvc.perform(put("/api/v1/admin/projects/" + projectId + "/budget").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"" + month + "\",\"amount\":0.01,\"alertThresholdPct\":100}"))
                .andExpect(status().isOk());

        // Before the rule exists nothing evaluates.
        alertEvaluator.evaluateAll();
        assertThat(eventCount()).isEqualTo(0);

        MvcResult created = mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"budget-fire","type":"BUDGET_THRESHOLD","threshold":80,
                         "scopeJson":"{\\"projectId\\":\\"%s\\"}"}
                        """.formatted(projectId))).andExpect(status().isOk()).andReturn();
        String ruleId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // Watermark is 100% >= 80 -> event fired.
        alertEvaluator.evaluateAll();
        assertThat(eventCount()).isEqualTo(1);
        java.math.BigDecimal value = jdbc.queryForObject("SELECT value FROM alert_events WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", UUID.fromString(ruleId)), java.math.BigDecimal.class);
        assertThat(value).isEqualByComparingTo("100");

        // Same month -> deduplicated, still exactly one event.
        alertEvaluator.evaluateAll();
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("BUDGET_THRESHOLD rules require a scope pointing at an existing tenant project")
    void budgetAlertScopeValidation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"no-scope\",\"type\":\"BUDGET_THRESHOLD\",\"threshold\":80}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCOPE_INVALID"));

        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad-scope\",\"type\":\"BUDGET_THRESHOLD\",\"threshold\":80,"
                        + "\"scopeJson\":\"{\\\"projectId\\\":\\\"" + UUID.randomUUID() + "\\\"}\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCOPE_INVALID"));

        // An existing project passes.
        UUID projectId = seedProject("BUD", "Budget Project");
        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok-scope\",\"type\":\"BUDGET_THRESHOLD\",\"threshold\":80,"
                        + "\"scopeJson\":\"{\\\"projectId\\\":\\\"" + projectId + "\\\"}\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scopeJson").isNotEmpty());
    }

    private long eventCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events", new MapSqlParameterSource(), Long.class);
        return count != null ? count : 0;
    }

    // ------------------------------------------------------------------

    private UUID seedProject(String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:id, :tenantId, :code, :name, 'ACTIVE', :tag, 0)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", SEED_TENANT).addValue("code", code)
                .addValue("name", name).addValue("tag", code.toLowerCase()));
        return id;
    }

    /** Seeds one month usage event + price snapshots so the watermark is real. */
    private void seedUsage(UUID projectId, String modelId, long inputTokens, long outputTokens) {
        UUID productId = jdbc.queryForObject("SELECT id FROM provider_products ORDER BY display_name LIMIT 1",
                new MapSqlParameterSource(), UUID.class);
        jdbc.update("""
                INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency, unit_price)
                VALUES (:id, :productId, :modelId, :tokenType, 'CNY', :unitPrice)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                        .addValue("modelId", modelId).addValue("tokenType", "INPUT")
                        .addValue("unitPrice", new java.math.BigDecimal("2")));
        jdbc.update("""
                INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency, unit_price)
                VALUES (:id, :productId, :modelId, :tokenType, 'CNY', :unitPrice)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                        .addValue("modelId", modelId).addValue("tokenType", "OUTPUT")
                        .addValue("unitPrice", new java.math.BigDecimal("8")));
        jdbc.update("""
                INSERT INTO usage_event
                    (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                     credential_id, model_id, cache_level, input_tokens, output_tokens, is_complete,
                     gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :requestId, :keyId, :projectId, :productId, null, :modelId, 'UPSTREAM',
                        :input, :output, TRUE, :gatewayId, now())
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", SEED_TENANT)
                        .addValue("requestId", UUID.randomUUID().toString()).addValue("keyId", UUID.randomUUID())
                        .addValue("projectId", projectId).addValue("productId", productId).addValue("modelId", modelId)
                        .addValue("input", inputTokens).addValue("output", outputTokens)
                        .addValue("gatewayId", UUID.randomUUID().toString()));
    }

    private static Cookie cookie(MvcResult result, String name) {
        return java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
    }
}
