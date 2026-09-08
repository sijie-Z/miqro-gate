package com.miqroera.miqrokey.controlplane.controller;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Open admin Virtual Key delegation (ADR-0016 增补 2026-09-09, F60 批 2 v2 案 1): a
 * machine key delegates key creation for a target user while every self-service
 * invariant is evaluated against that target — ownership lands on the target,
 * the member boundary still applies (non-members are rejected), the full secret
 * appears once, and the audit records both halves (actor = issuing admin,
 * {@code targetUserId} in the summary).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Open admin virtual key delegation integration tests (PostgreSQL)")
class OpenAdminVirtualKeysApiIntegrationTest {

    static final String TAG = "core-ai";
    static final String MODEL_A = "claude-3-7-sonnet";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.gateway-base-url", () -> "https://gateway.test.internal");
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
    private String machineSecret;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        adminUserId = UUID.fromString(String.valueOf(bootBody.get("userId")));
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        // Issue a real machine key through the session surface, exactly as the
        // delegating admin would; its issuer is the bootstrap SYSTEM_ADMIN.
        MvcResult issued = mockMvc.perform(
                post("/api/v1/admin/api-keys").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ops-vkey\"}"))
                .andExpect(status().isCreated()).andReturn();
        machineSecret = String
                .valueOf(objectMapper.readValue(issued.getResponse().getContentAsString(), Map.class).get("secret"));
        assertThat(machineSecret).startsWith("mqk_admin_");
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("machine key delegates creation: key belongs to the target member, audit keeps both halves")
    void delegateCreatesKeyOwnedByTarget() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID targetId = fx.insertMemberUser("alice", "USER", "ACTIVE");

        MvcResult r = postDelegated(targetId, "claude-code-alice").andExpect(status().isCreated())
                .andExpect(jsonPath("$.shownOnce").value(true))
                .andExpect(jsonPath("$.baseUrl").value("https://gateway.test.internal")).andReturn();
        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        String secret = String.valueOf(body.get("secret"));
        UUID keyId = UUID.fromString(String.valueOf(body.get("id")));
        assertThat(secret).startsWith("mqk_live_").endsWith("." + TAG);

        // Ownership lands on the target, not the machine or the issuing admin.
        MapSqlParameterSource p = new MapSqlParameterSource("id", keyId);
        assertThat(jdbc.queryForObject("SELECT user_id FROM virtual_keys WHERE id = :id", p, UUID.class))
                .isEqualTo(targetId);
        String boundProject = jdbc.queryForObject(
                "SELECT project_id FROM key_project_binding WHERE virtual_key_id = :id AND status = 'ACTIVE'", p,
                String.class);
        assertThat(boundProject).isEqualTo(fx.projectId.toString());

        // Audit: actor = issuing admin (machine never impersonates), summary
        // carries the target for the ownership half of the chain.
        String auditActor = jdbc.queryForObject("""
                SELECT actor_id FROM admin_audit_events
                WHERE action = 'VIRTUAL_KEY_CREATE' AND target_id = :id
                """, p, UUID.class).toString();
        assertThat(auditActor).isEqualTo(adminUserId.toString());
        String summary = jdbc.queryForObject("""
                SELECT change_summary::text FROM admin_audit_events
                WHERE action = 'VIRTUAL_KEY_CREATE' AND target_id = :id
                """, p, String.class);
        assertThat(summary).contains("targetUserId").contains(targetId.toString());

        // Open list by userId exposes only safe metadata — no secret column.
        mockMvc.perform(get("/api/v1/admin-api/virtual-keys").param("userId", targetId.toString())
                .header("Authorization", "Bearer " + machineSecret)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(keyId.toString()))
                .andExpect(jsonPath("$[0].projectTag").value(TAG)).andExpect(jsonPath("$[0].secret").doesNotExist());
    }

    @Test
    @DisplayName("delegation rejects a target who is not a project member (case-1 boundary)")
    void delegateRejectsNonMemberTarget() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID outsiderId = fx.insertMemberUser("bob", "USER", "ACTIVE");
        fx.clearMemberships();

        postDelegated(outsiderId, "k").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_MEMBERSHIP_REQUIRED"));
        assertThat(keyCount()).isZero();
    }

    @Test
    @DisplayName("delegation to a SYSTEM_ADMIN target is exempt from membership, like self-service")
    void delegateToSystemAdminTargetIsExempt() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID otherAdminId = fx.insertMemberUser("boss", "SYSTEM_ADMIN", "ACTIVE");
        fx.clearMemberships();

        postDelegated(otherAdminId, "admin-key").andExpect(status().isCreated());
        MapSqlParameterSource p = new MapSqlParameterSource("id", otherAdminId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM virtual_keys WHERE user_id = :id", p, Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("delegation rejects an inactive or cross-tenant or unknown target")
    void delegateRejectsUnavailableTargets() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID disabledId = fx.insertMemberUser("carol", "USER", "DISABLED");
        UUID foreignId = fx.insertForeignUser("dave");

        postDelegated(disabledId, "k").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARGET_USER_INACTIVE"));
        postDelegated(foreignId, "k").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_USER_NOT_FOUND"));
        postDelegated(UUID.randomUUID(), "k").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_USER_NOT_FOUND"));
        assertThat(keyCount()).isZero();
    }

    @Test
    @DisplayName("delegation still runs the full creation chain (project, grant, models, tag)")
    void delegateKeepsCreationInvariants() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(null); // no routing tag
        UUID targetId = fx.insertMemberUser("erin", "USER", "ACTIVE");

        postDelegated(targetId, "k").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTING_TAG_MISSING"));
        assertThat(keyCount()).isZero();
    }

    @Test
    @DisplayName("delegation requires a machine credential and a valid request")
    void delegationRequiresCredential() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID targetId = fx.insertMemberUser("frank", "USER", "ACTIVE");

        mockMvc.perform(post("/api/v1/admin-api/virtual-keys").contentType(MediaType.APPLICATION_JSON)
                .content(delegatedBody(targetId, "k"))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin-api/virtual-keys").header("Authorization", "Bearer mqk_admin_unknown")
                .contentType(MediaType.APPLICATION_JSON).content(delegatedBody(targetId, "k")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin-api/virtual-keys").header("Authorization", "Bearer " + machineSecret)
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + targetId
                        + "\",\"purpose\":\"CLAUDE_CODE\",\"projectId\":\"" + fx.projectId + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(keyCount()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions postDelegated(UUID targetId, String name)
            throws Exception {
        return mockMvc.perform(post("/api/v1/admin-api/virtual-keys").header("Authorization", "Bearer " + machineSecret)
                .contentType(MediaType.APPLICATION_JSON).content(delegatedBody(targetId, name)));
    }

    private String delegatedBody(UUID targetId, String name) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("userId", targetId, "name", name, "projectId", fx.projectId, "providerProductId", fx.productId,
                        "credentialGrantId", fx.grantId, "purpose", "CLAUDE_CODE", "allowedModels", List.of(MODEL_A)));
    }

    private int keyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM virtual_keys", new MapSqlParameterSource(), Integer.class);
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

    /** Direct JDBC fixtures: catalog, project, grant, credential, users. */
    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID otherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID grantCreatorId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                    "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships", "projects",
                    "provider_products", "providers", "admin_api_keys", "admin_audit_events", "user_sessions",
                    "users")) {
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

        void insertProjectWithGrant(String tag) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("tenantId", tenantId).addValue("projectId", projectId).addValue("subscriptionId", subscriptionId)
                    .addValue("credentialId", credentialId).addValue("grantId", grantId)
                    .addValue("productId", productId).addValue("tag", tag).addValue("grantCreatorId", grantCreatorId);
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', :tag, 0)
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
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :grantCreatorId, 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, :model)
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId).addValue("model",
                    MODEL_A));
        }

        /** Inserts a tenant user and makes them a member of the project. */
        UUID insertMemberUser(String username, String role, String status) {
            UUID userId = insertUser(tenantId, username, role, status);
            jdbc.update("""
                    INSERT INTO project_memberships (tenant_id, project_id, user_id)
                    VALUES (:tenantId, :projectId, :userId)
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("projectId", projectId)
                    .addValue("userId", userId));
            return userId;
        }

        UUID insertUser(UUID tenantId, String username, String role, String status) {
            jdbc.update("""
                    INSERT INTO tenants (id, code, name) VALUES (:tenantId, :code, :name) ON CONFLICT (id) DO NOTHING
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("code", "it-" + tenantId.hashCode())
                    .addValue("name", "IT tenant " + tenantId));
            UUID userId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:id, :tenantId, :username, :username, decode('a1b2c3d4', 'hex'), :role, :status, FALSE, 0)
                    """, new MapSqlParameterSource("id", userId).addValue("tenantId", tenantId)
                    .addValue("username", username).addValue("role", role).addValue("status", status));
            return userId;
        }

        UUID insertForeignUser(String username) {
            return insertUser(otherTenantId, username, "USER", "ACTIVE");
        }

        void clearMemberships() {
            jdbc.update("DELETE FROM project_memberships WHERE project_id = :projectId",
                    new MapSqlParameterSource("projectId", projectId));
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
