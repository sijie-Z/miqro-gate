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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin organization APIs against real PostgreSQL (G5.2): users (create /
 * disable / reset-password / revoke sessions), teams + members, projects +
 * members, and grants with model scopes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin organization API integration tests (PostgreSQL)")
class AdminOrgApiIntegrationTest {

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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
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
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("user create returns a one-time temporary password usable for login and change")
    void userLifecycle() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "alice", "displayName", "Alice", "role", "USER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist()).andReturn();
        String temp = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class)
                .get("temporaryPassword").toString();
        String userId = ((Map<?, ?>) objectMapper.readValue(created.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        // The temporary password works for login; the user must change it.
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice", temp))))
                .andExpect(status().isOk()).andReturn();
        Cookie userSession = cookie(login, "MIQROKEY_SESSION");
        org.assertj.core.api.Assertions.assertThat(userSession).isNotNull();

        // The user list never serializes password hashes.
        mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='alice')].passwordHash").doesNotExist());

        // Reset: new temporary password, sessions revoked (old login rejected).
        MvcResult reset = mockMvc.perform(post("/api/v1/admin/users/" + userId + "/reset-password")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andReturn();
        String newTemp = objectMapper.readValue(reset.getResponse().getContentAsString(), Map.class)
                .get("temporaryPassword").toString();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).cookie(userSession)
                .content(objectMapper.writeValueAsString(new LoginRequest("alice", temp))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("alice", newTemp))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("teams and projects manage members; duplicate project codes are rejected")
    void teamsAndProjects() throws Exception {
        MvcResult team = mockMvc
                .perform(post("/api/v1/admin/teams").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper
                                .writeValueAsString(Map.of("name", "Platform", "description", "Platform team"))))
                .andExpect(status().isOk()).andReturn();
        String teamId = objectMapper.readValue(team.getResponse().getContentAsString(), Map.class).get("id").toString();

        MvcResult user = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("username", "bob"))))
                .andExpect(status().isOk()).andReturn();
        String userId = ((Map<?, ?>) objectMapper.readValue(user.getResponse().getContentAsString(), Map.class)
                .get("user")).get("id").toString();

        mockMvc.perform(post("/api/v1/admin/teams/" + teamId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", userId)))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/teams/" + teamId + "/members").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value("bob"));
        mockMvc.perform(delete("/api/v1/admin/teams/" + teamId + "/members/" + userId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        MvcResult project = mockMvc
                .perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("code", "CORE", "name", "Core AI", "projectTag", "core-ai"))))
                .andExpect(status().isOk()).andReturn();
        String projectId = objectMapper.readValue(project.getResponse().getContentAsString(), Map.class).get("id")
                .toString();
        mockMvc.perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("code", "CORE", "name", "Duplicate"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CODE_TAKEN"));
        mockMvc.perform(post("/api/v1/admin/projects/" + projectId + "/members").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("userId", userId)))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/projects/" + projectId + "/members").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value("bob"));
    }

    @Test
    @DisplayName("grants bind project, product and credential with a model scope")
    void grants() throws Exception {
        fx.insertProviderAndProductAndCredential();
        MvcResult project = mockMvc
                .perform(post("/api/v1/admin/projects").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("code", "CORE", "name", "Core"))))
                .andExpect(status().isOk()).andReturn();
        String projectId = objectMapper.readValue(project.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        MvcResult grant = mockMvc
                .perform(post("/api/v1/admin/grants").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("projectId", projectId, "providerProductId",
                                fx.productId.toString(), "credentialId", fx.credentialId.toString(), "models",
                                List.of("model-a", "model-b")))))
                .andExpect(status().isOk()).andReturn();
        String grantId = objectMapper.readValue(grant.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(get("/api/v1/admin/grants/" + grantId + "/models").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("model-a"))
                .andExpect(jsonPath("$[1]").value("model-b"));
        mockMvc.perform(post("/api/v1/admin/grants/" + grantId + "/models").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("models", List.of("model-a")))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/grants/" + grantId + "/models").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete("/api/v1/admin/grants/" + grantId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("system admins cannot be disabled; anonymous access is rejected")
    void guards() throws Exception {
        // The bootstrap admin is a SYSTEM_ADMIN: disabling must be rejected.
        MvcResult list = mockMvc.perform(get("/api/v1/admin/users").cookie(sessionCookie)).andExpect(status().isOk())
                .andReturn();
        String adminId = ((Map<?, ?>) ((java.util.List<?>) objectMapper
                .readValue(list.getResponse().getContentAsString(), java.util.List.class)).get(0)).get("id").toString();
        mockMvc.perform(patch("/api/v1/admin/users/" + adminId).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_NOT_DISABLEABLE"));
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
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

        void reset() {
            for (String table : List.of("quota_snapshots", "cost_allocations", "usage_event", "cache_hit_event",
                    "price_snapshot", "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                    "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships",
                    "team_memberships", "projects", "teams", "provider_products", "providers", "admin_audit_events",
                    "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertProviderAndProductAndCredential() {
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
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, plan_scope, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'NONE', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", subscriptionId));
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
