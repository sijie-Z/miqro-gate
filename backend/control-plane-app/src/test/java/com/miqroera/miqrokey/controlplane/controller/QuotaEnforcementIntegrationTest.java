package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.QuotaEnforcementService;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import com.miqroera.miqrokey.route.JdbcRouteSnapshotLoader;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quota soft landing (#684 block ①, V59 {@code quota_enforcement}): a rule only
 * refuses traffic once it opts into REJECT and its watermark reaches the limit,
 * and the refusal is materialised as one row per scope that the gateway reads
 * from its route snapshot.
 *
 * <p>
 * Covers the four contracts of the block: a REJECT rule over the limit produces
 * a block row; raising the limit or leaving the window removes it again; an
 * ALERT rule stays inert however far over the line it is; and the snapshot
 * loader publishes the live blocks (and only the live ones) to the gateway.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Quota soft-landing enforcement integration tests (#684, PostgreSQL)")
class QuotaEnforcementIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    QuotaEnforcementService enforcementService;

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private UUID adminUserId;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "enf_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
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
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    // ------------------------------------------------------------------
    // ① a REJECT rule over the limit blocks its scope
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REJECT rule at the limit materialises one block row for its scope")
    void rejectRuleAtLimitBlocksScope() throws Exception {
        UUID keyId = usageFixture();
        fx.insertUsage(keyId, 600L, 400L); // 1000 tokens against a limit of 500 => 200%

        UUID ruleId = ruleIdOf(putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 500, 80, "REJECT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enforcement").value("REJECT"))
                .andExpect(jsonPath("$.level").value("EXCEEDED")));

        // The rule is a statement of intent; nothing is enforced until the
        // evaluator runs, which is exactly what the periodic tick drives.
        assertThat(blockRows(adminUserId)).isEmpty();
        assertThat(enforcementService.reconcile(TENANT_ID)).isTrue();

        Map<String, Object> row = singleBlockRow(adminUserId);
        assertThat(row.get("scope_type")).isEqualTo("USER");
        assertThat(row.get("rule_id")).isEqualTo(ruleId);
        assertThat(row.get("metric")).isEqualTo("TOKENS");
        assertThat(row.get("period")).isEqualTo("DAILY");
        assertThat(((Number) row.get("limit_value")).longValue()).isEqualTo(500L);
        assertThat((BigDecimal) row.get("used_value")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) row.get("used_percent")).isEqualByComparingTo("200.00");
        Instant windowFrom = ((Timestamp) row.get("window_from")).toInstant();
        Instant windowTo = ((Timestamp) row.get("window_to")).toInstant();
        assertThat(windowFrom).isBefore(windowTo);
        assertThat(Instant.now()).isAfterOrEqualTo(windowFrom).isBefore(windowTo);

        // Re-running the cycle on an unchanged state must be a no-op: the tick
        // fires every minute and must not rewrite rows (or notify) each time.
        assertThat(enforcementService.reconcile(TENANT_ID)).isFalse();
        assertThat(blockRows(adminUserId)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // ② the block is released when the scope stops qualifying
    // ------------------------------------------------------------------

    @Test
    @DisplayName("raising the limit above usage, or a rolled-over window, removes the block row")
    void blockReleasedWhenScopeStopsQualifying() throws Exception {
        UUID keyId = usageFixture();
        fx.insertUsage(keyId, 600L, 400L);
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 500, 80, "REJECT")).andExpect(status().isOk());
        assertThat(enforcementService.reconcile(TENANT_ID)).isTrue();
        assertThat(blockRows(adminUserId)).hasSize(1);

        // Same tuple, higher limit, enforcement omitted: the stored REJECT mode
        // survives the edit and the scope stops qualifying at 20%.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 5000, 80, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enforcement").value("REJECT")).andExpect(jsonPath("$.level").value("NORMAL"));
        assertThat(enforcementService.reconcile(TENANT_ID)).isTrue();
        assertThat(blockRows(adminUserId)).isEmpty();

        // A row left behind by a previous window is swept by the next cycle
        // even though its scope has no rule of its own any more.
        UUID staleScope = UUID.randomUUID();
        seedClosedWindowRow(ruleIdOf(), staleScope);
        assertThat(blockRows(staleScope)).hasSize(1);

        assertThat(enforcementService.reconcile(TENANT_ID)).isTrue();
        assertThat(blockRows(staleScope)).isEmpty();
    }

    // ------------------------------------------------------------------
    // ③ ALERT rules stay alerting-only, however far over the line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ALERT rule over its limit produces no block row")
    void alertRuleNeverBlocks() throws Exception {
        UUID keyId = usageFixture();
        fx.insertUsage(keyId, 600L, 400L);

        // Default mode on create is ALERT, and the watermark still reports the
        // rule as EXCEEDED — alerting and blocking are separate decisions.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 500, 80, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enforcement").value("ALERT")).andExpect(jsonPath("$.level").value("EXCEEDED"));
        assertThat(enforcementService.reconcile(TENANT_ID)).isFalse();
        assertThat(blockRows(adminUserId)).isEmpty();

        // Asking for ALERT explicitly changes nothing.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 500, 80, "ALERT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enforcement").value("ALERT"));
        assertThat(enforcementService.reconcile(TENANT_ID)).isFalse();
        assertThat(countBlockRows()).isZero();
    }

    // ------------------------------------------------------------------
    // ④ the route snapshot carries the live blocks (and only the live ones)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("route snapshot exposes the blocked user and project scopes")
    void snapshotCarriesBlockedScopes() throws Exception {
        UUID keyId = usageFixture();
        fx.insertUsage(keyId, 600L, 400L);
        UUID userRuleId = ruleIdOf(
                putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 500, 80, "REJECT")).andExpect(status().isOk()));
        putQuota(quotaBody("PROJECT", fx.projectId, "TOKENS", "DAILY", 500, 80, "REJECT")).andExpect(status().isOk());
        assertThat(enforcementService.reconcile(TENANT_ID)).isTrue();

        RouteSnapshot snapshot = new JdbcRouteSnapshotLoader(jdbc, objectMapper).load(1L, Instant.now());
        assertThat(snapshot.blockedUsers(TENANT_ID)).containsExactly(adminUserId);
        assertThat(snapshot.blockedProjects(TENANT_ID)).containsExactly(fx.projectId);
        assertThat(snapshot.userBlocked(TENANT_ID, adminUserId)).isTrue();
        assertThat(snapshot.projectBlocked(TENANT_ID, fx.projectId)).isTrue();
        assertThat(snapshot.blocked(TENANT_ID, adminUserId, UUID.randomUUID())).isTrue();
        assertThat(snapshot.blocked(TENANT_ID, UUID.randomUUID(), fx.projectId)).isTrue();
        assertThat(snapshot.blocked(TENANT_ID, UUID.randomUUID(), UUID.randomUUID())).isFalse();
        assertThat(snapshot.blocked(TENANT_ID, null, null)).isFalse();
        // Another tenant's snapshot view stays empty: blocks are tenant-scoped.
        assertThat(snapshot.blockedUsers(UUID.randomUUID())).isEmpty();

        // Fail-open: a row from a closed window never reaches the gateway, even
        // if the evaluator has not swept it yet.
        seedClosedWindowRow(userRuleId, UUID.randomUUID());
        RouteSnapshot reloaded = new JdbcRouteSnapshotLoader(jdbc, objectMapper).load(2L, Instant.now());
        assertThat(reloaded.blockedUsers(TENANT_ID)).containsExactly(adminUserId);
    }

    // ------------------------------------------------------------------
    // ⑤ an unknown enforcement value never reaches the database
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown enforcement value is refused with 400 PARAM_INVALID and stores nothing")
    void unknownEnforcementIsRefused() throws Exception {
        // The enum is the only way to opt into blocking, so a typo must fail
        // loudly instead of silently degrading a REJECT intent to ALERT.
        putQuota(quotaBody("USER", adminUserId, "TOKENS", "DAILY", 500, 80, "BLOCK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.detail").value(containsString("enforcement")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM quota_rules WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", TENANT_ID), Long.class)).isZero();
        assertThat(enforcementService.reconcile(TENANT_ID)).isFalse();
        assertThat(countBlockRows()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Provisions the catalog/project/key chain a usage event needs. */
    private UUID usageFixture() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant();
        return fx.createKeyViaAdmin();
    }

    private ResultActions putQuota(String body) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/quota-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String quotaBody(String scopeType, UUID scopeId, String metric, String period, long limitValue,
            Integer warnPercent, String enforcement) {
        StringBuilder sb = new StringBuilder("{\"scopeType\":\"").append(scopeType).append("\",\"scopeId\":\"")
                .append(scopeId).append("\",\"metric\":\"").append(metric).append("\",\"period\":\"").append(period)
                .append("\",\"limitValue\":").append(limitValue);
        if (warnPercent != null) {
            sb.append(",\"warnPercent\":").append(warnPercent);
        }
        if (enforcement != null) {
            sb.append(",\"enforcement\":\"").append(enforcement).append('"');
        }
        return sb.append('}').toString();
    }

    private UUID ruleIdOf(ResultActions put) throws Exception {
        return UUID.fromString(
                (String) objectMapper.readValue(put.andReturn().getResponse().getContentAsString(), Map.class).get("id"));
    }

    /** The tenant's single rule id (the tests above only ever create one). */
    private UUID ruleIdOf() {
        return jdbc.queryForObject("SELECT id FROM quota_rules WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", TENANT_ID), UUID.class);
    }

    /** A block row from a window that has already closed. */
    private void seedClosedWindowRow(UUID ruleId, UUID scopeId) {
        jdbc.update("""
                INSERT INTO quota_enforcement (id, tenant_id, scope_type, scope_id, rule_id, metric, period,
                                               limit_value, used_value, used_percent, window_from, window_to,
                                               created_at, updated_at)
                VALUES (:id, :tenantId, 'USER', :scopeId, :ruleId, 'TOKENS', 'DAILY', 500, 800, 160.00,
                        :windowFrom, :windowTo, now(), now())
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT_ID)
                .addValue("scopeId", scopeId).addValue("ruleId", ruleId)
                // pgjdbc cannot infer a type for java.time.Instant: bind Timestamp.
                .addValue("windowFrom", Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)))
                .addValue("windowTo", Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS))));
    }

    private List<Map<String, Object>> blockRows(UUID scopeId) {
        return jdbc.queryForList("""
                SELECT scope_type, scope_id, rule_id, metric, period, limit_value, used_value, used_percent,
                       window_from, window_to
                FROM quota_enforcement WHERE tenant_id = :tenantId AND scope_id = :scopeId
                """, new MapSqlParameterSource("tenantId", TENANT_ID).addValue("scopeId", scopeId));
    }

    private Map<String, Object> singleBlockRow(UUID scopeId) {
        List<Map<String, Object>> rows = blockRows(scopeId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long countBlockRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM quota_enforcement", new MapSqlParameterSource(),
                Long.class);
        return count != null ? count : 0;
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

    /** Direct JDBC fixtures: catalog, project/grant chain, user, usage rows. */
    private final class Fixture {
        final UUID tenantId = TENANT_ID;
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID adminSeedId = UUID.randomUUID();
        UUID keyId;

        void reset() {
            // quota_enforcement references quota_rules: it has to go first.
            for (String table : List.of("quota_enforcement", "webhook_delivery_attempts", "alert_events", "alert_rules",
                    "usage_event", "price_snapshot", "quota_rules", "virtual_key_models", "key_project_binding",
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

        void insertUsage(UUID keyId, long input, long output) {
            MapSqlParameterSource p = new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                    .addValue("keyId", keyId).addValue("projectId", projectId).addValue("productId", productId)
                    .addValue("modelId", "model-alpha").addValue("input", input).addValue("output", output);
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, is_complete,
                         gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :requestId, :keyId, :projectId, :productId, null, :modelId, 'UPSTREAM',
                            :input, :output, TRUE, :gatewayId, now())
                    """, p.addValue("requestId", UUID.randomUUID().toString()).addValue("gatewayId",
                    UUID.randomUUID().toString()));
        }
    }
}
