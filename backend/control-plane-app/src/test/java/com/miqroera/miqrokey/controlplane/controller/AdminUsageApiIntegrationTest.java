package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

        void reset() {
            for (String table : List.of("usage_event", "cache_hit_event", "price_snapshot", "virtual_key_models",
                    "key_project_binding", "model_approval", "virtual_keys", "project_provider_grant_models",
                    "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "plan_seats",
                    "upstream_subscriptions", "project_memberships", "projects", "provider_products", "providers",
                    "admin_audit_events", "user_sessions", "users")) {
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
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :productId, :credentialId, :model,
                            'UPSTREAM', :input, :output, :total, 42, 200, TRUE, FALSE, 'greq', :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("keyId", keyId)
                            .addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId).addValue("model", model).addValue("input", input)
                            .addValue("output", output).addValue("total", input + output)
                            .addValue("occurredAt", Timestamp.from(Instant.now())));
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
