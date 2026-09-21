package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import com.miqroera.miqrokey.controlplane.service.AlertEvaluator;
import com.miqroera.miqrokey.controlplane.service.QuotaEnforcementService;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
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
 * Quota rules (V23, roadmap "quota management" step): plan CRUD keyed on
 * (scope, metric, period) with live watermarks derived from usage events —
 * TOKENS totals and upstream REQUESTS per UTC window. Rules with action ALERT
 * only report; REJECT rules feed the soft-landing evaluator (#684).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin quota rule API integration tests (PostgreSQL)")
class AdminQuotaRuleApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    PasswordHasher passwordHasher;
    @Autowired
    AlertEvaluator alertEvaluator;
    @Autowired
    QuotaEnforcementService quotaEnforcementService;

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private Cookie userSession;
    private UUID adminUserId;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        adminUserId = UUID.fromString((String) bootBody.get("userId"));
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        fx.insertRegularUser("NewSecurePass1!");
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("regular_user", "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        userSession = cookie(login, "MIQROKEY_SESSION");
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rule lifecycle: put upserts on the (scope, metric, period) key and delete removes")
    void lifecycle() throws Exception {
        String body = quotaBody("USER", adminUserId, "TOKENS", "DAILY", 10000, 80);
        MvcResult put = putQuota(body).andExpect(status().isOk()).andExpect(jsonPath("$.level").value("NORMAL"))
                .andExpect(jsonPath("$.used").value(0)).andExpect(jsonPath("$.scopeName").value("Admin"))
                .andExpect(jsonPath("$.usedPct").value(0.0)).andReturn();
        String id = (String) objectMapper.readValue(put.getResponse().getContentAsString(), Map.class).get("id");

        mockMvc.perform(get("/api/v1/admin/quota-rules").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].metric").value("TOKENS"));

        // Same tuple re-PUT updates the plan in place: same id, version bump.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 20000, 50)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.limitValue").value(20000)).andExpect(jsonPath("$.warnPercent").value(50));

        // A different tuple creates a second rule.
        putQuota(quotaBody("USER", adminUserId, "REQUESTS", "DAILY", 100, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.warnPercent").value(80));
        mockMvc.perform(get("/api/v1/admin/quota-rules").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(delete("/api/v1/admin/quota-rules/" + id).cookie(adminSession, adminCsrf).header("X-CSRF-Token",
                adminCsrfToken)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/admin/quota-rules/" + id).cookie(adminSession, adminCsrf).header("X-CSRF-Token",
                adminCsrfToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTA_RULE_NOT_FOUND"));
    }

    @Test
    @DisplayName("watermark derives token usage into NORMAL/WARNING/NEAR_LIMIT/EXCEEDED")
    void tokenWatermarkLevels() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        fx.insertUsage(keyId, 600L, 400L); // 1000 tokens today

        // Same scope, one rule per period (each with a different limit).
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 10000, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1000)).andExpect(jsonPath("$.usedPct").value(10.0))
                .andExpect(jsonPath("$.level").value("NORMAL"));
        // 90.91% crosses the fixed 90% guidance tier (#683) even below a 99% warn.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "WEEKLY", 1100, 99)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1000)).andExpect(jsonPath("$.usedPct").value(90.91))
                .andExpect(jsonPath("$.level").value("NEAR_LIMIT"));
        // 50% of the request limit crosses the rule's own warn threshold.
        putQuota(quotaBody("USER", adminUserId, "REQUESTS", "DAILY", 2, 40)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1)).andExpect(jsonPath("$.usedPct").value(50.0))
                .andExpect(jsonPath("$.level").value("WARNING"));
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "MONTHLY", 1000, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1000)).andExpect(jsonPath("$.usedPct").value(100.0))
                .andExpect(jsonPath("$.level").value("EXCEEDED"));

        // Disabled rules keep their watermark row.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 10000, 80, "DISABLED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.level").value("NORMAL"));
    }

    @Test
    @DisplayName("#1002: a usage adjustment moves the reporting net but must not move the TOKENS quota watermark")
    void adjustmentDoesNotMoveTokenWatermark() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        String gatewayRequestId = fx.insertUsage(keyId, 600L, 400L); // 1000 observed tokens today

        // A REJECT rule the observed 1000 tokens already exceed.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 800, 80, null, "REJECT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1000)).andExpect(jsonPath("$.usedPct").value(125.0))
                .andExpect(jsonPath("$.level").value("EXCEEDED"));
        quotaEnforcementService.evaluate();
        assertThat(enforcementRows("USER", adminUserId)).isEqualTo(1);

        // A financial correction against the observed fact: net output 400 -> 100.
        mockMvc.perform(
                post("/api/v1/admin/usage-adjustments")
                        .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gatewayRequestId\":\"" + gatewayRequestId
                                + "\",\"outputTokensDelta\":-300," + "\"reason\":\"上游账单修正\"}"))
                .andExpect(status().isCreated());

        // The reporting reading absorbs the correction ...
        mockMvc.perform(get("/api/v1/admin/usage/summary").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.tokens.output").value(100));

        // ... the runtime control reading must not (V63 layer 4): the watermark stays
        // on the observed 1000, so the scope stays blocked.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 800, 80, null, "REJECT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1000)).andExpect(jsonPath("$.usedPct").value(125.0))
                .andExpect(jsonPath("$.level").value("EXCEEDED"));
        quotaEnforcementService.evaluate();
        assertThat(enforcementRows("USER", adminUserId)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQUEST metric counts upstream requests; PROJECT scope filters by project")
    void requestMetricAndProjectScope() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        fx.insertUsage(keyId, 10L, 5L);
        fx.insertUsage(keyId, 1L, 1L);
        fx.insertUsage(keyId, 2L, 2L);

        putQuota(quotaBody("USER", adminUserId, "REQUESTS", "DAILY", 2, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(3)).andExpect(jsonPath("$.level").value("EXCEEDED"));
        putQuota(quotaBody("PROJECT", fx.projectId, "REQUESTS", "DAILY", 5, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(3)).andExpect(jsonPath("$.level").value("NORMAL"))
                .andExpect(jsonPath("$.scopeName").value("Project One"));
        // An unknown project scope is a 404.
        putQuota(quotaBody("PROJECT", UUID.randomUUID(), "REQUESTS", "DAILY", 5, 80)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("COST metric watermarks the priced upstream cost of the window (#683)")
    void costMetricWatermarkLevels() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        fx.insertUsage(keyId, 1_000_000L, 500_000L); // 1M input + 0.5M output
        fx.insertPrices(new BigDecimal("1.00"), new BigDecimal("2.00")); // => 1.00 + 1.00 = CNY 2.00

        putQuota(quotaBody("USER", adminUserId, "COST", "MONTHLY", 2, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(2.00)).andExpect(jsonPath("$.usedPct").value(100.0))
                .andExpect(jsonPath("$.level").value("EXCEEDED"));
        putQuota(quotaBody("USER", adminUserId, "COST", "DAILY", 4, 40)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(2.00)).andExpect(jsonPath("$.usedPct").value(50.0))
                .andExpect(jsonPath("$.level").value("WARNING"));
    }

    /**
     * #943: the COST reading is a *priced* sum, and nothing in the response said
     * so. A window whose usage has no price snapshot read as a definite CNY 0.00 /
     * NORMAL — the same shape as "this scope spent nothing" — while the usage page
     * called the same events unpriced. The watermark now carries the pricing status
     * the usage API has sent since #766, so both pages can render the number as the
     * lower bound it is.
     *
     * <p>
     * The enforcement reading (level / exceeded) is deliberately untouched: what an
     * unpriceable window should do to a REJECT rule is #943 question 2, the owner's
     * call, not a side effect of teaching the page to say "lower bound".
     */
    @Test
    @DisplayName("#943: a COST watermark over unpriceable usage reports the gap, not a definite zero")
    void costWatermarkReportsPricingGap() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        fx.insertUsage(keyId, 1_000_000L, 500_000L); // no price snapshot: nothing here can be valued

        // The unpriceable reading: the number is 0 and the level says NORMAL — that
        // half is unchanged; what is new is that the response admits the gap.
        putQuota(quotaBody("USER", adminUserId, "COST", "MONTHLY", 1, 80, null, "REJECT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(0)).andExpect(jsonPath("$.level").value("NORMAL"))
                .andExpect(jsonPath("$.pricingStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.unpriced.unpricedEvents").value(1));

        // A TOKENS rule makes no cost claim, so it carries no cost caveat: the same
        // 1.5M tokens are counted in full however the window happens to be priced.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "MONTHLY", 2_000_000, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1_500_000)).andExpect(jsonPath("$.pricingStatus").doesNotExist());

        // Price the model, then add an event for a model that has none: the window is
        // now PARTIAL, and the 2.00 it shows is a floor — the usage page and this page
        // finally describe the same window the same way.
        fx.insertPrices(new BigDecimal("1.00"), new BigDecimal("2.00")); // => CNY 1.00 + 1.00
        fx.insertUsage(keyId, "model-beta", 11L, 7L);

        putQuota(quotaBody("USER", adminUserId, "COST", "MONTHLY", 1, 80, null, "REJECT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(2.00)).andExpect(jsonPath("$.pricingStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.unpriced.unpricedEvents").value(1));
    }

    @Test
    @DisplayName("YEARLY period watermarks against the UTC calendar year (#683)")
    void yearlyPeriodWatermark() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        fx.insertUsage(keyId, 500L, 500L);

        MvcResult put = putQuota(quotaBody("USER", adminUserId, "TOKENS", "YEARLY", 1000, 80))
                .andExpect(status().isOk()).andExpect(jsonPath("$.used").value(1000))
                .andExpect(jsonPath("$.usedPct").value(100.0)).andExpect(jsonPath("$.level").value("EXCEEDED"))
                .andReturn();
        String windowFrom = (String) objectMapper.readValue(put.getResponse().getContentAsString(), Map.class)
                .get("windowFrom");
        String year = String.valueOf(java.time.LocalDate.now(java.time.ZoneOffset.UTC).getYear());
        org.assertj.core.api.Assertions.assertThat(windowFrom).startsWith(year + "-01-01");
    }

    @Test
    @DisplayName("soft landing (#684): an exceeded REJECT rule blocks its scope until the limit is raised")
    void softLandingEnforcementSet() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        fx.insertUsage(keyId, 600L, 400L); // 1000 tokens today

        // ALERT rules never produce a verdict, however far past the limit.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 100, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ALERT")).andExpect(jsonPath("$.level").value("EXCEEDED"));
        quotaEnforcementService.evaluate();
        assertThat(enforcementRows("USER", adminUserId)).isZero();

        // The same tuple re-PUT as REJECT blocks the scope on the next cycle.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 100, 80, null, "REJECT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("REJECT"));
        quotaEnforcementService.evaluate();
        assertThat(enforcementRows("USER", adminUserId)).isEqualTo(1);
        // A second cycle over an unchanged verdict set is a no-op.
        quotaEnforcementService.evaluate();
        assertThat(enforcementRows("USER", adminUserId)).isEqualTo(1);

        // Raising the limit clears the verdict on the next cycle.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 10_000, 80, null, "REJECT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.level").value("NORMAL"));
        quotaEnforcementService.evaluate();
        assertThat(enforcementRows("USER", adminUserId)).isZero();
    }

    private int enforcementRows(String scopeType, UUID scopeId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM quota_enforcement WHERE scope_type = :scopeType AND scope_id = :scopeId
                """, new MapSqlParameterSource("scopeType", scopeType).addValue("scopeId", scopeId), Integer.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // validation & permissions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("validation rejects bad limits, thresholds and enums")
    void validation() throws Exception {
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 0, 80)).andExpect(status().isBadRequest());
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", -5, 80)).andExpect(status().isBadRequest());
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 100, 0)).andExpect(status().isBadRequest());
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 100, 100)).andExpect(status().isBadRequest());
        putQuota(quotaBody("USER", UUID.randomUUID(), "TOKENS", "DAILY", 100, 80)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_FOUND"));
        mockMvc.perform(put("/api/v1/admin/quota-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content(quotaBody("USER", adminUserId, "BAD_METRIC", "DAILY", 100, 80)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("regular users and anonymous callers are rejected")
    void permissions() throws Exception {
        mockMvc.perform(get("/api/v1/admin/quota-rules").cookie(userSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/quota-rules")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/quota-rules").cookie(adminSession)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("rule changes are audited with create/update/delete actions")
    void auditTrail() throws Exception {
        MvcResult put = putQuota(quotaBody("USER", adminUserId, "TOKENS", "MONTHLY", 10000, 80))
                .andExpect(status().isOk()).andReturn();
        String id = (String) objectMapper.readValue(put.getResponse().getContentAsString(), Map.class).get("id");
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "MONTHLY", 20000, 90)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/quota-rules/" + id).cookie(adminSession, adminCsrf).header("X-CSRF-Token",
                adminCsrfToken)).andExpect(status().isNoContent());

        List<String> actions = jdbc.query(
                "SELECT action FROM admin_audit_events" + " WHERE action LIKE 'QUOTA_RULE_%' ORDER BY created_at",
                new MapSqlParameterSource(), (rs, i) -> rs.getString(1));
        assertThat(actions).containsExactly("QUOTA_RULE_CREATE", "QUOTA_RULE_UPDATE", "QUOTA_RULE_DELETE");
    }

    @Test
    @DisplayName("QUOTA_THRESHOLD rules fire once per reset window when the watermark crosses the threshold")
    void quotaAlertFiresOncePerWindow() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        UUID keyId = fx.createKeyViaAdmin();
        fx.insertUsage(keyId, 600L, 400L); // 1000 tokens in the current DAILY window

        MvcResult put = putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 1000, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usedPct").value(100.0)).andReturn();
        String ruleId = (String) objectMapper.readValue(put.getResponse().getContentAsString(), Map.class).get("id");

        // Nothing evaluates before the alert rule exists.
        alertEvaluator.evaluateAll();
        assertThat(eventCount()).isZero();

        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"quota-fire","type":"QUOTA_THRESHOLD","threshold":80,
                         "scopeJson":"{\\"quotaRuleId\\":\\"%s\\"}"}
                        """.formatted(ruleId))).andExpect(status().isOk());

        // Watermark 100% >= 80 -> one event for the current window.
        alertEvaluator.evaluateAll();
        assertThat(eventCount()).isEqualTo(1);
        java.math.BigDecimal value = jdbc.queryForObject("SELECT value FROM alert_events", new MapSqlParameterSource(),
                java.math.BigDecimal.class);
        assertThat(value).isEqualByComparingTo("100");

        // Same window -> deduplicated, still exactly one event.
        alertEvaluator.evaluateAll();
        assertThat(eventCount()).isEqualTo(1);

        // Disabling the quota rule stops new alerts (the event stays).
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 1000, 80, "DISABLED")).andExpect(status().isOk());
        alertEvaluator.evaluateAll();
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("QUOTA_THRESHOLD rules require a scope pointing at an existing tenant quota rule")
    void quotaAlertScopeValidation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"no-scope\",\"type\":\"QUOTA_THRESHOLD\",\"threshold\":80}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCOPE_INVALID"));

        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad-scope\",\"type\":\"QUOTA_THRESHOLD\",\"threshold\":80,"
                        + "\"scopeJson\":\"{\\\"quotaRuleId\\\":\\\"" + UUID.randomUUID() + "\\\"}\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCOPE_INVALID"));

        // An existing same-tenant quota rule passes.
        MvcResult put = putQuota(quotaBody("USER", adminUserId, "TOKENS", "MONTHLY", 10000, 80))
                .andExpect(status().isOk()).andReturn();
        String ruleId = (String) objectMapper.readValue(put.getResponse().getContentAsString(), Map.class).get("id");
        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok-scope\",\"type\":\"QUOTA_THRESHOLD\",\"threshold\":80,"
                        + "\"scopeJson\":\"{\\\"quotaRuleId\\\":\\\"" + ruleId + "\\\"}\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scopeJson").isNotEmpty());
    }

    @Test
    @DisplayName("deleting a quota rule referenced by a QUOTA_THRESHOLD rule is refused (I21)")
    void deleteBlockedByQuotaThresholdRuleReference() throws Exception {
        MvcResult put = putQuota(quotaBody("USER", adminUserId, "TOKENS", "MONTHLY", 10000, 80))
                .andExpect(status().isOk()).andReturn();
        String ruleId = (String) objectMapper.readValue(put.getResponse().getContentAsString(), Map.class).get("id");
        MvcResult alert = mockMvc
                .perform(post("/api/v1/admin/alert-rules").cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"quota-80pct\",\"type\":\"QUOTA_THRESHOLD\",\"threshold\":80,"
                                + "\"scopeJson\":\"{\\\"quotaRuleId\\\":\\\"" + ruleId + "\\\"}\"}"))
                .andExpect(status().isOk()).andReturn();
        String alertId = (String) objectMapper.readValue(alert.getResponse().getContentAsString(), Map.class).get("id");

        // Referenced by a live alert rule: the delete must not silently orphan the
        // reference (scope_json carries no FK, so nothing else would catch this).
        mockMvc.perform(delete("/api/v1/admin/quota-rules/" + ruleId).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.dependencies", hasSize(1)))
                .andExpect(jsonPath("$.dependencies[0].type").value("ALERT_RULE"))
                .andExpect(jsonPath("$.dependencies[0].id").value(alertId))
                .andExpect(jsonPath("$.dependencies[0].name").value("quota-80pct"));

        // Release the reference, then the delete succeeds.
        mockMvc.perform(delete("/api/v1/admin/alert-rules/" + alertId).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/quota-rules/" + ruleId).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isNoContent());
    }

    private long eventCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events", new MapSqlParameterSource(), Long.class);
        return count != null ? count : 0;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ResultActions putQuota(String body) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/quota-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String quotaBody(String scopeType, UUID scopeId, String metric, String period, long limitValue,
            Integer warnPercent) {
        return quotaBody(scopeType, scopeId, metric, period, limitValue, warnPercent, null);
    }

    private String quotaBody(String scopeType, UUID scopeId, String metric, String period, long limitValue,
            Integer warnPercent, String status) {
        return quotaBody(scopeType, scopeId, metric, period, limitValue, warnPercent, status, null);
    }

    /** #684: same body plus the exceeded action (ALERT | REJECT). */
    private String quotaBody(String scopeType, UUID scopeId, String metric, String period, long limitValue,
            Integer warnPercent, String status, String action) {
        StringBuilder sb = new StringBuilder("{\"scopeType\":\"").append(scopeType).append("\",\"scopeId\":\"")
                .append(scopeId).append("\",\"metric\":\"").append(metric).append("\",\"period\":\"").append(period)
                .append("\",\"limitValue\":").append(limitValue);
        if (warnPercent != null) {
            sb.append(",\"warnPercent\":").append(warnPercent);
        }
        if (status != null) {
            sb.append(",\"status\":\"").append(status).append('"');
        }
        if (action != null) {
            sb.append(",\"action\":\"").append(action).append('"');
        }
        return sb.append('}').toString();
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    /** Direct JDBC fixtures: catalog, project/grant chain, user, usage rows. */
    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID adminSeedId = UUID.randomUUID();
        final UUID regularUserId = UUID.randomUUID();
        UUID keyId;

        void reset() {
            for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "usage_event",
                    "price_snapshot", "quota_enforcement", "quota_rules", "virtual_key_models", "key_project_binding",
                    "model_approval", "virtual_keys", "project_provider_grant_models", "project_provider_grants",
                    "unattributed_policy", "upstream_credential_versions", "upstream_credentials", "plan_seats",
                    "upstream_subscriptions", "project_memberships", "project_repositories", "projects",
                    "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Child-first order above covers the canonical FK set.
                }
            }
        }

        void insertRegularUser(String password) {
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:id, :tenantId, 'regular_user', 'Regular', :hash, 'USER', 'ACTIVE', FALSE, 0)
                    """, new MapSqlParameterSource("id", regularUserId).addValue("tenantId", tenantId).addValue("hash",
                    passwordHasher.hash(password)));
        }

        void insertProviderCatalog() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("providerId", providerId).addValue("productId", productId);
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:providerId, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, p);
        }

        void insertProjectWithGrant() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("tenantId", tenantId).addValue("projectId", projectId).addValue("subscriptionId", subscriptionId)
                    .addValue("credentialId", credentialId).addValue("grantId", grantId)
                    .addValue("productId", productId).addValue("adminSeedId", adminSeedId);
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', 'core-ai', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:subscriptionId, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:credentialId, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :adminSeedId, 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, 'model-alpha')
                    """, p);
        }

        /** A Virtual Key owned by the bootstrap admin, bound to the fixture project. */
        UUID createKeyViaAdmin() throws Exception {
            MvcResult r = mockMvc.perform(post("/api/v1/me/virtual-keys").cookie(adminSession, adminCsrf)
                    .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", "claude-code-main", "projectId",
                            projectId.toString(), "providerProductId", productId.toString(), "credentialGrantId",
                            grantId.toString(), "purpose", "CLAUDE_CODE", "allowedModels", List.of("model-alpha")))))
                    .andExpect(status().isCreated()).andReturn();
            keyId = UUID.fromString(
                    (String) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("id"));
            return keyId;
        }

        void insertPrices(BigDecimal input, BigDecimal output) {
            for (Map.Entry<String, BigDecimal> e : List.of(Map.entry("INPUT", input), Map.entry("OUTPUT", output))) {
                jdbc.update("""
                        INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency,
                                                    unit_price, effective_from, source)
                        VALUES (gen_random_uuid(), :productId, 'model-alpha', :type, 'CNY', :price,
                                now() - interval '1 hour', 'MANUAL')
                        """, new MapSqlParameterSource("productId", productId).addValue("type", e.getKey())
                        .addValue("price", e.getValue()));
            }
        }

        /** Returns the {@code gateway_request_id}, the handle adjustments address. */
        String insertUsage(UUID keyId, long input, long output) {
            return insertUsage(keyId, "model-alpha", input, output);
        }

        /**
         * Same, for a model other than the one {@link #insertPrices} covers — the way a
         * window ends up priced in part (#943).
         */
        String insertUsage(UUID keyId, String modelId, long input, long output) {
            String gatewayRequestId = UUID.randomUUID().toString();
            MapSqlParameterSource p = new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                    .addValue("keyId", keyId).addValue("projectId", projectId).addValue("productId", productId)
                    .addValue("modelId", modelId).addValue("input", input).addValue("output", output);
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, is_complete,
                         gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :requestId, :keyId, :projectId, :productId, null, :modelId, 'UPSTREAM',
                            :input, :output, TRUE, :gatewayId, now())
                    """, p.addValue("requestId", UUID.randomUUID().toString()).addValue("gatewayId", gatewayRequestId));
            return gatewayRequestId;
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
