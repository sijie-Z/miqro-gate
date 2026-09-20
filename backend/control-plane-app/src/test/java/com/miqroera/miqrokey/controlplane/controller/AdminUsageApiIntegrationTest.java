package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.Assertions;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin-wide usage endpoints (G4.1) against real PostgreSQL: whole-tenant
 * aggregation with optional filters (user/project/key/credential/subscription/
 * vendor/model), paged records, and SYSTEM_ADMIN-only access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin usage API integration tests (PostgreSQL)")
class AdminUsageApiIntegrationTest {

    static final String MODEL = "claude-3-7-sonnet";
    static final String OTHER_MODEL = "deepseek-chat";

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

    private Cookie adminSession;
    private Cookie userSession;
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
        Cookie csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        String csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());

        // A regular USER with a real password hash, logged in for the 403 checks.
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

    @Test
    @DisplayName("admin summary sees the whole tenant, unlike the self-service endpoint")
    void adminSummarySeesWholeTenant() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertOtherUsersKey();
        fx.insertPrices();
        fx.insertUsage(ownKey, "chatcmpl-own-1", 1_000L, 500L);
        fx.insertUsage(fx.otherKeyId, "chatcmpl-other-1", 9_000L, 9_000L);

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "VIRTUAL_KEY").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(2));
        // Both keys' requests are visible to the admin.
        mockMvc.perform(get("/api/v1/admin/usage/summary").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.requests.upstream").value(2))
                .andExpect(jsonPath("$.totals.tokens.input").value(10_000))
                .andExpect(jsonPath("$.totals.tokens.output").value(9_500));
    }

    @Test
    @DisplayName("#1097: each cost split lands exactly on its own total, and the two splits cover different rows")
    void costSplitReconcilesWithItsTotals() throws Exception {
        fx.insertCatalogAndGrant();
        fx.insertPrices(); // INPUT 1.00 / OUTPUT 2.00 per 1M tokens
        UUID key = fx.createOwnKey();
        fx.insertUsage(key, "chatcmpl-split-up", 1_000_000L, 0L); // 1.00 reached the provider
        fx.insertUsageOnProject(key, fx.projectId, "chatcmpl-split-co", 0L, 500_000L, MODEL, Instant.now(),
                "COALESCED");

        String body = mockMvc.perform(get("/api/v1/admin/usage/summary").cookie(adminSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode cost = objectMapper.readTree(body).path("totals").path("cost");

        // The console draws a bar under these figures; if the segments do not add up to
        // the number they sit under, the reader cannot tell a rounding artefact from a
        // discrepancy in the data. Exact compare — the parts are summed from the same
        // per-row amounts as the total.
        Assertions.assertThat(sumParts(cost.path("upstreamPaidParts")))
                .as("upstreamPaidParts must add up to upstreamPaid")
                .isEqualByComparingTo(cost.path("upstreamPaid").decimalValue());
        Assertions.assertThat(sumParts(cost.path("gatewayObservedParts")))
                .as("gatewayObservedParts must add up to gatewayObserved")
                .isEqualByComparingTo(cost.path("gatewayObserved").decimalValue());

        // The coalesced row was served by the gateway, never paid upstream: it belongs
        // to
        // the observed split only. One split could not describe both totals.
        Assertions.assertThat(cost.path("upstreamPaid").decimalValue()).isEqualByComparingTo("1");
        Assertions.assertThat(cost.path("gatewayObserved").decimalValue()).isEqualByComparingTo("2");
        Assertions.assertThat(cost.path("gatewayObservedParts").path("output").decimalValue())
                .isEqualByComparingTo("1");
        Assertions.assertThat(cost.path("upstreamPaidParts").path("output").decimalValue()).isEqualByComparingTo("0");
        Assertions.assertThat(cost.path("upstreamPaidParts").path("input").decimalValue()).isEqualByComparingTo("1");
    }

    private static BigDecimal sumParts(JsonNode parts) {
        return parts.path("input").decimalValue().add(parts.path("output").decimalValue())
                .add(parts.path("cacheRead").decimalValue()).add(parts.path("cacheCreation").decimalValue());
    }

    @Test
    @DisplayName("admin summary filters by user")
    void adminSummaryFiltersByUser() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertOtherUsersKey();
        fx.insertPrices();
        fx.insertUsage(ownKey, "chatcmpl-own-1", 1_000L, 500L);
        fx.insertUsage(fx.otherKeyId, "chatcmpl-other-1", 9_000L, 9_000L);

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("userId", fx.userId.toString()).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.requests.upstream").value(1))
                .andExpect(jsonPath("$.totals.tokens.input").value(1_000));
        mockMvc.perform(
                get("/api/v1/admin/usage/summary").param("userId", fx.otherUserId.toString()).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.requests.upstream").value(1))
                .andExpect(jsonPath("$.totals.tokens.input").value(9_000));
    }

    @Test
    @DisplayName("admin summary filters by model and by virtual key")
    void adminSummaryFiltersByModelAndKey() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertPrices();
        fx.insertUsage(ownKey, "chatcmpl-own-1", 1_000L, 500L);
        fx.insertUsage(ownKey, "chatcmpl-own-2", 200L, 100L, MODEL);
        fx.insertUsage(ownKey, "chatcmpl-other-model", 7_000L, 3_000L, OTHER_MODEL);

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("modelId", OTHER_MODEL).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.requests.upstream").value(1))
                .andExpect(jsonPath("$.totals.tokens.input").value(7_000));
        mockMvc.perform(
                get("/api/v1/admin/usage/summary").param("virtualKeyId", ownKey.toString()).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.requests.upstream").value(3));
    }

    @Test
    @DisplayName("admin records filter and paginate over the whole tenant")
    void adminRecordsFilterAndPaginate() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertOtherUsersKey();
        fx.insertUsage(ownKey, "chatcmpl-own-1", 1_000L, 500L);
        fx.insertUsage(fx.otherKeyId, "chatcmpl-other-1", 9_000L, 9_000L);

        mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(
                get("/api/v1/admin/usage/records").param("userId", fx.otherUserId.toString()).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].providerRequestId").value("chatcmpl-other-1"));
    }

    @Test
    @DisplayName("records carry provider name, per-row cost, first byte and protocol (#758)")
    void recordsCarryLifecycleEnrichment() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertPrices();
        Instant pricedAt = Instant.now().minusSeconds(120);
        Instant unpricedAt = Instant.now().minusSeconds(60);
        // Priced row: 1000×1.00/1e6 + 500×2.00/1e6 = 0.002 USD; priced and fully wired.
        fx.insertUsageWithGatewayId(ownKey, "chatcmpl-enr-1", "greq-enr-1", 1_000L, 500L, MODEL, pricedAt);
        fx.insertLifecycle(ownKey, "greq-enr-1", "SUCCEEDED", 210L, 4_900L, pricedAt);
        // Unpriced model (no snapshot): priced=false; failure terminal without a first
        // byte.
        fx.insertUsageWithGatewayId(ownKey, "chatcmpl-enr-2", "greq-enr-2", 7_000L, 3_000L, OTHER_MODEL, unpricedAt);
        fx.insertLifecycle(ownKey, "greq-enr-2", "UPSTREAM_REJECTED", null, 1_200L, unpricedAt);

        MvcResult r = mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        // Newest first: unpriced OTHER_MODEL row, then the priced MODEL row.
        JsonNode items = objectMapper.readTree(r.getResponse().getContentAsString()).path("items");
        JsonNode unpriced = items.get(0);
        JsonNode priced = items.get(1);

        Assertions.assertThat(unpriced.path("modelId").asText()).isEqualTo(OTHER_MODEL);
        Assertions.assertThat(unpriced.path("providerProductName").asText()).isEqualTo("Test Product");
        Assertions.assertThat(unpriced.path("priced").asBoolean()).isFalse();
        Assertions.assertThat(unpriced.path("requestStatus").asText()).isEqualTo("UPSTREAM_REJECTED");
        Assertions.assertThat(unpriced.path("ttfbMs").isNull()).isTrue();

        Assertions.assertThat(priced.path("modelId").asText()).isEqualTo(MODEL);
        Assertions.assertThat(priced.path("priced").asBoolean()).isTrue();
        Assertions.assertThat(priced.path("cost").decimalValue()).isEqualByComparingTo("0.002");
        Assertions.assertThat(priced.path("ttfbMs").asLong()).isEqualTo(210L);
        Assertions.assertThat(priced.path("wireProtocol").asText()).isEqualTo("ANTHROPIC_MESSAGES");
        Assertions.assertThat(priced.path("requestStatus").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("records survive a product missing from the catalog (LEFT JOIN, never inner) (#758)")
    void recordsSurviveMissingCatalogProduct() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertUsageWithOrphanProduct(ownKey, "chatcmpl-orphan", "greq-orphan", 100L, 50L, Instant.now());

        MvcResult r = mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        JsonNode item = objectMapper.readTree(r.getResponse().getContentAsString()).path("items").get(0);
        Assertions.assertThat(item.path("inputTokens").asLong()).isEqualTo(100L);
        Assertions.assertThat(item.path("providerProductName").isNull()).isTrue();
    }

    @Test
    @DisplayName("groupBy=PRODUCT carries success rate and average latency from the lifecycle (#758)")
    void productGroupByCarriesOutcomes() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        Instant t = Instant.now().minusSeconds(300);
        // Four forwarded calls: two succeeded (ttfb 1s / 2s), one upstream failure, one
        // client cancel.
        fx.insertUsageWithGatewayId(ownKey, "chatcmpl-out-1", "greq-out-1", 100L, 10L, MODEL, t);
        fx.insertLifecycle(ownKey, "greq-out-1", "SUCCEEDED", 1_000L, 3_000L, t);
        fx.insertUsageWithGatewayId(ownKey, "chatcmpl-out-2", "greq-out-2", 100L, 10L, MODEL, t);
        fx.insertLifecycle(ownKey, "greq-out-2", "SUCCEEDED", 2_000L, 5_000L, t);
        fx.insertUsageWithGatewayId(ownKey, "chatcmpl-out-3", "greq-out-3", 100L, 10L, MODEL, t);
        fx.insertLifecycle(ownKey, "greq-out-3", "UPSTREAM_REJECTED", null, 7_000L, t);
        fx.insertUsageWithGatewayId(ownKey, "chatcmpl-out-4", "greq-out-4", 100L, 10L, MODEL, t);
        fx.insertLifecycle(ownKey, "greq-out-4", "CLIENT_CANCELLED", null, 1_000L, t);

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "PRODUCT").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].label").value("Test Product"))
                .andExpect(jsonPath("$.groups[0].requests.upstream").value(4))
                // succeeded = forwarded − failed − cancelled = 4 − 1 − 1
                .andExpect(jsonPath("$.groups[0].outcomes.succeeded").value(2))
                .andExpect(jsonPath("$.groups[0].outcomes.failed").value(1))
                .andExpect(jsonPath("$.groups[0].outcomes.cancelled").value(1))
                // avg over the four durations (3000+5000+7000+1000)/4 = 4000
                .andExpect(jsonPath("$.groups[0].outcomes.avgDurationMs").value(4_000))
                // ttfb averaged over the two rows that observed it: (1000+2000)/2 = 1500
                .andExpect(jsonPath("$.groups[0].outcomes.avgTtfbMs").value(1_500))
                .andExpect(jsonPath("$.totals.outcomes.succeeded").value(2));
    }

    @Test
    @DisplayName("a call that started before the window but finished inside it keeps its outcome (#1132)")
    void lifecycleSurvivesLeftEdgeOfWindow() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        // The window a report asks for: one hour. The fact row is selected on
        // occurred_at, which ProxyController stamps when the response completes;
        // the lifecycle row carries started_at, stamped before the upstream call.
        Instant from = Instant.parse("2026-09-15T10:00:00Z");
        Instant to = from.plusSeconds(3_600);
        Instant completedAt = from.plusSeconds(5);
        // One forwarded call: started 10s before the window, completed 5s inside it,
        // rejected upstream (no first byte) after 15s.
        fx.insertUsageWithGatewayId(ownKey, "chatcmpl-bd-1", "greq-bd-1", 100L, 10L, MODEL, completedAt);
        fx.insertLifecycle(ownKey, "greq-bd-1", "UPSTREAM_REJECTED", null, 15_000L, completedAt.minusSeconds(15));

        // The report for that window: one forwarded request, and it failed.
        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "PRODUCT")
                .param("from", from.toString()).param("to", to.toString()).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].requests.upstream").value(1))
                .andExpect(jsonPath("$.groups[0].outcomes.failed").value(1))
                .andExpect(jsonPath("$.groups[0].outcomes.succeeded").value(0))
                // The dropped lifecycle row also left the duration average: its one
                // sample is the only one in the window.
                .andExpect(jsonPath("$.groups[0].outcomes.avgDurationMs").value(15_000));

        // The detail list of the same window must describe the same call the same way.
        MvcResult r = mockMvc.perform(get("/api/v1/admin/usage/records").param("from", from.toString())
                .param("to", to.toString()).cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        JsonNode item = objectMapper.readTree(r.getResponse().getContentAsString()).path("items").get(0);
        Assertions.assertThat(item.path("requestStatus").asText()).isEqualTo("UPSTREAM_REJECTED");
        Assertions.assertThat(item.path("wireProtocol").asText()).isEqualTo("ANTHROPIC_MESSAGES");
    }

    @Test
    @DisplayName("regular users are forbidden from admin usage endpoints")
    void nonAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usage/summary").cookie(userSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/usage/records").cookie(userSession)).andExpect(status().isForbidden());
        // Anonymous is rejected at the session layer.
        mockMvc.perform(get("/api/v1/admin/usage/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admin usage endpoints validate parameters like the self-service ones")
    void validationErrors() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "bogus").cookie(adminSession))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GROUP_BY_INVALID"));
        mockMvc.perform(get("/api/v1/admin/usage/records").param("page", "0").cookie(adminSession))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_INVALID"));
        mockMvc.perform(get("/api/v1/admin/usage/records").param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-12-31T00:00:00Z").cookie(adminSession)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_TOO_WIDE"));
        // Malformed UUID filters are rejected as bad requests.
        mockMvc.perform(get("/api/v1/admin/usage/records").param("userId", "not-a-uuid").cookie(adminSession))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID userId = UUID.randomUUID();
        final UUID otherUserId = UUID.randomUUID();
        final UUID otherKeyId = UUID.randomUUID();
        final UUID secondProjectId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("usage_event", "cache_hit_event", "request_usage_records", "price_snapshot",
                    "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                    "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                    "project_memberships", "project_repositories", "projects", "provider_products", "providers",
                    "admin_audit_events", "team_memberships", "teams", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertRegularUser(String password) {
            MapSqlParameterSource p = new MapSqlParameterSource("id", userId).addValue("tenantId", tenantId)
                    .addValue("hash", passwordHasher.hash(password));
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:id, :tenantId, 'regular_user', 'Regular', :hash, 'USER', 'ACTIVE', FALSE, 0)
                    """, p);
        }

        void insertCatalogAndGrant() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', 'core-ai', 0)
                    """, new MapSqlParameterSource("projectId", projectId).addValue("tenantId", tenantId));
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", subscriptionId));
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE',
                            '00000000-0000-0000-0000-000000000000', 0)
                    """,
                    new MapSqlParameterSource("grantId", grantId).addValue("tenantId", tenantId)
                            .addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId));
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, :model)
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId).addValue("model",
                    MODEL));
        }

        /** A key owned by {@link #userId} (the fixture's regular user). */
        UUID createOwnKey() {
            UUID keyId = UUID.randomUUID();
            MapSqlParameterSource p = new MapSqlParameterSource("keyId", keyId).addValue("tenantId", tenantId)
                    .addValue("userId", userId).addValue("projectId", projectId).addValue("grantId", grantId)
                    .addValue("credentialId", credentialId);
            jdbc.update("""
                    INSERT INTO virtual_keys
                        (id, tenant_id, public_key_id, secret_digest, display_prefix, last_four, user_id, project_id,
                         grant_id, upstream_credential_id, purpose, name, cache_policy, status, version)
                    VALUES (:keyId, :tenantId, 'pk-own', decode('00', 'hex'), 'pre', '0001', :userId,
                            :projectId, :grantId, :credentialId, 'CLAUDE_CODE', 'own', 'DISABLED', 'ACTIVE', 0)
                    """, p);
            return keyId;
        }

        /** A key owned by another user, with a login-able account. */
        void insertOtherUsersKey() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("otherUserId", otherUserId).addValue("tenantId", tenantId).addValue("otherKeyId", otherKeyId)
                    .addValue("projectId", projectId).addValue("grantId", grantId)
                    .addValue("credentialId", credentialId).addValue("hash", passwordHasher.hash("NewSecurePass1!"));
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:otherUserId, :tenantId, 'other_user', 'Other', :hash, 'USER', 'ACTIVE', FALSE, 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO virtual_keys
                        (id, tenant_id, public_key_id, secret_digest, display_prefix, last_four, user_id, project_id,
                         grant_id, upstream_credential_id, purpose, name, cache_policy, status, version)
                    VALUES (:otherKeyId, :tenantId, 'pk-other', decode('00', 'hex'), 'pre', '0000', :otherUserId,
                            :projectId, :grantId, :credentialId, 'CLAUDE_CODE', 'other', 'DISABLED', 'ACTIVE', 0)
                    """, p);
        }

        void insertPrices() {
            Instant effective = Instant.parse("2026-01-01T00:00:00Z");
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'INPUT', 'USD', 1.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", Timestamp.from(effective)));
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'OUTPUT', 'USD', 2.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", Timestamp.from(effective)));
        }

        void insertUsage(UUID keyId, String providerRequestId, long input, long output) {
            insertUsage(keyId, providerRequestId, input, output, MODEL);
        }

        void insertUsage(UUID keyId, String providerRequestId, long input, long output, String model) {
            insertUsage(keyId, providerRequestId, input, output, model, Instant.now());
        }

        void insertUsage(UUID keyId, String providerRequestId, long input, long output, String model,
                Instant occurredAt) {
            insertUsageOnProject(keyId, projectId, providerRequestId, input, output, model, occurredAt);
        }

        /** Usage on an explicit project (#634 hourly cross-tab tests). */
        void insertUsageOnProject(UUID keyId, UUID onProjectId, String providerRequestId, long input, long output,
                Instant occurredAt) {
            insertUsageOnProject(keyId, onProjectId, providerRequestId, input, output, MODEL, occurredAt);
        }

        void insertUsageOnProject(UUID keyId, UUID onProjectId, String providerRequestId, long input, long output,
                String model, Instant occurredAt) {
            insertUsageOnProject(keyId, onProjectId, providerRequestId, input, output, model, occurredAt, "UPSTREAM");
        }

        /**
         * Same, with the row's cache level spelled out: a COALESCED row never reached
         * the provider, which is exactly the difference between the two cost splits
         * (#1097).
         */
        void insertUsageOnProject(UUID keyId, UUID onProjectId, String providerRequestId, long input, long output,
                String model, Instant occurredAt, String cacheLevel) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :productId, :credentialId, :model,
                            :cacheLevel, :input, :output, :total, 42, 200, TRUE, FALSE, 'greq', :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("keyId", keyId)
                            .addValue("projectId", onProjectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId).addValue("model", model).addValue("input", input)
                            .addValue("output", output).addValue("total", input + output)
                            .addValue("cacheLevel", cacheLevel).addValue("occurredAt", Timestamp.from(occurredAt)));
        }

        /** A second project so the hour x project grouping is observable (#634). */
        void insertSecondProject() {
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:id, :tenantId, 'P2', 'Project Two', 'ACTIVE', 'core-ai-2', 0)
                    """, new MapSqlParameterSource("id", secondProjectId).addValue("tenantId", tenantId));
        }

        /**
         * A usage fact row with an explicit gateway request id so a lifecycle row can
         * be wired to it (#758).
         */
        void insertUsageWithGatewayId(UUID keyId, String providerRequestId, String gatewayRequestId, long input,
                long output, String model, Instant occurredAt) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :productId, :credentialId, :model,
                            'UPSTREAM', :input, :output, :total, 42, 200, TRUE, FALSE, :greq, :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("greq", gatewayRequestId)
                            .addValue("keyId", keyId).addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId).addValue("model", model).addValue("input", input)
                            .addValue("output", output).addValue("total", input + output)
                            .addValue("occurredAt", Timestamp.from(occurredAt)));
        }

        /**
         * Usage on a product with no catalog row — {@code usage_event} carries no FK on
         * {@code provider_product_id}, and #709's fixtures seed exactly this shape. The
         * read side must keep such rows (LEFT JOIN, never inner) (#758).
         */
        void insertUsageWithOrphanProduct(UUID keyId, String providerRequestId, String gatewayRequestId, long input,
                long output, Instant occurredAt) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :orphanProductId, :credentialId,
                            :model, 'UPSTREAM', :input, :output, :total, 42, 200, TRUE, FALSE, :greq, :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("greq", gatewayRequestId)
                            .addValue("keyId", keyId).addValue("projectId", projectId)
                            .addValue("orphanProductId", UUID.randomUUID()).addValue("credentialId", credentialId)
                            .addValue("model", MODEL).addValue("input", input).addValue("output", output)
                            .addValue("total", input + output).addValue("occurredAt", Timestamp.from(occurredAt)));
        }

        /**
         * The lifecycle trail row of one forwarded call (#758): what the stats read
         * enriches by gateway request id — protocol, first byte, terminal status.
         */
        void insertLifecycle(UUID keyId, String gatewayRequestId, String requestStatus, Long ttfbMs, Long durationMs,
                Instant startedAt) {
            Instant firstByteAt = ttfbMs == null ? null : startedAt.plusMillis(ttfbMs);
            Instant completedAt = durationMs == null ? null : startedAt.plusMillis(durationMs);
            jdbc.update("""
                    INSERT INTO request_usage_records
                        (started_at, id, gateway_request_id, tenant_id, user_id, project_id, virtual_key_id,
                         provider_id, provider_product_id, credential_id, model_id, wire_protocol, streaming,
                         request_status, first_byte_at, completed_at, duration_ms, time_to_first_byte_ms, http_status)
                    VALUES (:startedAt, :id, :greq, :tenantId, :userId, :projectId, :keyId, :providerId, :productId,
                            :credentialId, :model, 'ANTHROPIC_MESSAGES', FALSE, :status, :firstByteAt, :completedAt,
                            :durationMs, :ttfbMs, 200)
                    """, new MapSqlParameterSource("startedAt", Timestamp.from(startedAt))
                    .addValue("id", UUID.randomUUID()).addValue("greq", gatewayRequestId).addValue("tenantId", tenantId)
                    .addValue("userId", userId).addValue("projectId", projectId).addValue("keyId", keyId)
                    .addValue("providerId", providerId).addValue("productId", productId)
                    .addValue("credentialId", credentialId).addValue("model", MODEL).addValue("status", requestStatus)
                    .addValue("firstByteAt", firstByteAt == null ? null : Timestamp.from(firstByteAt))
                    .addValue("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
                    .addValue("durationMs", durationMs).addValue("ttfbMs", ttfbMs));
        }

        /**
         * Team "Alpha" with both fixture users as members (#634). Returns the team id.
         */
        UUID insertTeamForBothUsers() {
            UUID teamId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO teams (id, tenant_id, name, status, version)
                    VALUES (:id, :tenantId, 'Alpha', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", teamId).addValue("tenantId", tenantId));
            for (UUID member : List.of(userId, otherUserId)) {
                jdbc.update("""
                        INSERT INTO team_memberships (tenant_id, team_id, user_id)
                        VALUES (:tenantId, :teamId, :userId)
                        """, new MapSqlParameterSource("tenantId", tenantId).addValue("teamId", teamId)
                        .addValue("userId", member));
            }
            return teamId;
        }
    }

    @Test
    @DisplayName("hourly crosses hours with users and projects in the caller's timezone (#634)")
    void hourlyCrossTabsUsersProjectsAndTimezone() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertOtherUsersKey();
        fx.insertSecondProject();
        fx.insertUsage(ownKey, "chatcmpl-h-1", 1_000L, 100L, MODEL, Instant.parse("2026-09-15T06:10:00Z"));
        fx.insertUsage(ownKey, "chatcmpl-h-2", 2_000L, 200L, MODEL, Instant.parse("2026-09-15T06:50:00Z"));
        fx.insertUsageOnProject(ownKey, fx.secondProjectId, "chatcmpl-h-3", 500L, 50L,
                Instant.parse("2026-09-15T07:05:00Z"));
        fx.insertUsage(fx.otherKeyId, "chatcmpl-h-4", 7_000L, 700L, MODEL, Instant.parse("2026-09-15T06:20:00Z"));

        // UTC+8: 06:10Z/06:20Z/06:50Z all fall into the 14:00 local hour (06:00Z
        // bucket).
        mockMvc.perform(get("/api/v1/admin/usage/hourly").cookie(adminSession).param("date", "2026-09-15")
                .param("tzOffsetMinutes", "480").param("dimension", "USER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-09-15")).andExpect(jsonPath("$.days").value(1))
                .andExpect(jsonPath("$.dimension").value("USER")).andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[*].hourStart",
                        containsInAnyOrder("2026-09-15T06:00:00Z", "2026-09-15T06:00:00Z", "2026-09-15T07:00:00Z")))
                .andExpect(jsonPath("$.rows[*].dimensionLabel",
                        containsInAnyOrder("regular_user", "other_user", "regular_user")))
                .andExpect(jsonPath("$.rows[*].projectLabel",
                        containsInAnyOrder("Project One", "Project One", "Project Two")))
                .andExpect(jsonPath("$.rows[*].requests", containsInAnyOrder(2, 1, 1)))
                .andExpect(jsonPath("$.rows[*].totalTokens", containsInAnyOrder(3_300, 7_700, 550)));
    }

    @Test
    @DisplayName("hourly team dimension aggregates all members per hour (#634)")
    void hourlyTeamDimension() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertOtherUsersKey();
        fx.insertTeamForBothUsers();
        fx.insertUsage(ownKey, "chatcmpl-t-1", 1_000L, 100L, MODEL, Instant.parse("2026-09-15T06:10:00Z"));
        fx.insertUsage(fx.otherKeyId, "chatcmpl-t-2", 7_000L, 700L, MODEL, Instant.parse("2026-09-15T06:20:00Z"));
        fx.insertUsage(ownKey, "chatcmpl-t-3", 2_000L, 200L, MODEL, Instant.parse("2026-09-15T07:50:00Z"));

        mockMvc.perform(get("/api/v1/admin/usage/hourly").cookie(adminSession).param("date", "2026-09-15")
                .param("tzOffsetMinutes", "480").param("dimension", "TEAM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[*].dimensionLabel", containsInAnyOrder("Alpha", "Alpha")))
                .andExpect(jsonPath("$.rows[*].requests", containsInAnyOrder(2, 1)))
                .andExpect(jsonPath("$.rows[*].totalTokens", containsInAnyOrder(8_800, 2_200)));
    }

    @Test
    @DisplayName("summary and records filter by teamId through the members' keys (#681)")
    void teamFilterScopesSummaryAndRecords() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertOtherUsersKey();
        UUID teamId = fx.insertTeamForBothUsers();
        fx.insertUsage(ownKey, "chatcmpl-tf-1", 1_000L, 100L);
        fx.insertUsage(fx.otherKeyId, "chatcmpl-tf-2", 7_000L, 700L);

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "team").param("teamId", teamId.toString())
                .cookie(adminSession)).andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].label").value("Alpha"))
                .andExpect(jsonPath("$.groups[0].requests.upstream").value(2))
                .andExpect(jsonPath("$.totals.tokens.input").value(8_000));
        // A team nobody belongs to narrows to nothing.
        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "team")
                .param("teamId", UUID.randomUUID().toString()).cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0));
        mockMvc.perform(get("/api/v1/admin/usage/records").param("teamId", teamId.toString()).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(
                get("/api/v1/admin/usage/records").param("teamId", UUID.randomUUID().toString()).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("hourly enforces admin-only access and parameter bounds (#634)")
    void hourlyValidationAndAccess() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usage/hourly").cookie(userSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/usage/hourly").cookie(adminSession).param("days", "8"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DAYS_INVALID"));
        mockMvc.perform(get("/api/v1/admin/usage/hourly").cookie(adminSession).param("dimension", "BOGUS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DIMENSION_INVALID"));
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

    @Test
    @DisplayName("summary groups by consumer and by model (I15)")
    void summaryGroupsByUserAndModel() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertOtherUsersKey();
        fx.insertPrices();
        fx.insertUsage(ownKey, "chatcmpl-own-1", 1_000L, 500L);
        fx.insertUsage(fx.otherKeyId, "chatcmpl-other-1", 9_000L, 9_000L);
        fx.insertUsage(ownKey, "chatcmpl-own-2", 200L, 100L, OTHER_MODEL);

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "USER").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[?(@.label=='regular_user')].tokens.input").value(contains(1_200)))
                .andExpect(jsonPath("$.groups[?(@.label=='other_user')].tokens.input").value(contains(9_000)));

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "MODEL").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[*].label", containsInAnyOrder(MODEL, OTHER_MODEL)))
                .andExpect(jsonPath("$.groups[?(@.label=='" + MODEL + "')].requests.upstream").value(contains(2)))
                .andExpect(
                        jsonPath("$.groups[?(@.label=='" + OTHER_MODEL + "')].requests.upstream").value(contains(1)));
    }

    @Test
    @DisplayName("summary groups by calendar month (I15)")
    void summaryGroupsByMonth() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertPrices();
        fx.insertUsage(ownKey, "chatcmpl-jul-1", 1_000L, 500L, MODEL, Instant.parse("2026-07-15T10:00:00Z"));
        fx.insertUsage(ownKey, "chatcmpl-aug-1", 2_000L, 1_000L, MODEL, Instant.parse("2026-08-15T10:00:00Z"));

        // Groups sort by label, so July precedes August.
        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "MONTH")
                .param("from", "2026-07-01T00:00:00Z").param("to", "2026-09-01T00:00:00Z").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[*].label", containsInAnyOrder("2026-07", "2026-08")))
                .andExpect(jsonPath("$.groups[0].label").value("2026-07"))
                .andExpect(jsonPath("$.groups[0].tokens.input").value(1_000))
                .andExpect(jsonPath("$.groups[1].tokens.input").value(2_000));
    }

    @Test
    @DisplayName("day buckets follow tzOffsetMinutes, and an out-of-range offset is rejected (#1050)")
    void dayBucketsFollowTheCallersOffset() throws Exception {
        fx.insertCatalogAndGrant();
        UUID ownKey = fx.createOwnKey();
        fx.insertPrices();
        // 2026-09-03 in UTC; 2026-09-04 00:30 at +08 — the row the console used to draw
        // a day early,
        // because the request log beside the chart prints local timestamps.
        fx.insertUsage(ownKey, "chatcmpl-tz-1", 1_000L, 500L, MODEL, Instant.parse("2026-09-03T16:30:00Z"));

        String from = "2026-09-01T00:00:00Z";
        String to = "2026-09-10T00:00:00Z";

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "DAY").param("from", from).param("to", to)
                .cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].label", contains("2026-09-03")));

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "DAY").param("tzOffsetMinutes", "480")
                .param("from", from).param("to", to).cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].label", contains("2026-09-04")));

        mockMvc.perform(get("/api/v1/admin/usage/summary").param("groupBy", "DAY").param("tzOffsetMinutes", "1081")
                .param("from", from).param("to", to).cookie(adminSession)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TZ_OFFSET_INVALID"));
    }
}
