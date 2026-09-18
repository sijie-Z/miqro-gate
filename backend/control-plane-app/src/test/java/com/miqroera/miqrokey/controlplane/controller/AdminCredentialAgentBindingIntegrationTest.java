package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.crypto.CredentialFingerprint;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #714: a credential referenced by an ACTIVE agent is an immutable identity —
 * {@code rotate} (change the secret) and {@code disable} (the lifecycle-ending
 * operation; there is no credential DELETE endpoint) are rejected with 409
 * {@code CREDENTIAL_REFERENCED_BY_AGENT} until the binding agent is disabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Agent-referenced credential immutability integration tests (#714)")
class AdminCredentialAgentBindingIntegrationTest {

    static final String SECRET = "sk-ant-test-secret-1234567890";
    static final String SECRET_2 = "sk-ant-test-secret-0987654321";

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
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
        fx.insertCatalog();
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    // ------------------------------------------------------------------
    // 引用 -> 改被拒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rotate is rejected with 409 while an ACTIVE agent references the credential")
    void rotateIsRejectedWhileAnAgentReferencesTheCredential() throws Exception {
        UUID credentialId = fx.createCredential("prod-key", SECRET);
        bindAgent("客服助手", credentialId);

        rotate(credentialId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_REFERENCED_BY_AGENT"))
                .andExpect(jsonPath("$.detail")
                        .value(allOf(containsString("客服助手"), containsString("不能轮换"))));

        // Nothing was written: creation left the credential at version 1 with a single
        // ACTIVE version, and the rejected rotate moved neither.
        Map<String, Object> credential = row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId);
        assertThat(credential.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) credential.get("version")).longValue()).isEqualTo(1L);
        List<Map<String, Object>> versions = rows(
                "SELECT * FROM upstream_credential_versions WHERE credential_id = :id", credentialId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat((byte[]) versions.get(0).get("secret_fingerprint"))
                .isEqualTo(CredentialFingerprint.sha256(SECRET));
        assertThat(actionsFor(credentialId)).containsExactly("CREDENTIAL_CREATE");
    }

    // ------------------------------------------------------------------
    // 引用 -> 删（停用）被拒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("disable is rejected with 409 while an ACTIVE agent references the credential")
    void disableIsRejectedWhileAnAgentReferencesTheCredential() throws Exception {
        UUID credentialId = fx.createCredential("prod-key", SECRET);
        bindAgent("客服助手", credentialId);

        disable(credentialId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_REFERENCED_BY_AGENT"))
                .andExpect(jsonPath("$.detail")
                        .value(allOf(containsString("客服助手"), containsString("不能停用"))));

        Map<String, Object> credential = row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId);
        assertThat(credential.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) credential.get("version")).longValue()).isEqualTo(1L);
        // The serving version was not drained, so the credential stays routable.
        assertThat(rows("SELECT * FROM upstream_credential_versions WHERE credential_id = :id", credentialId))
                .singleElement().extracting(v -> v.get("status")).isEqualTo("ACTIVE");
        assertThat(actionsFor(credentialId)).containsExactly("CREDENTIAL_CREATE");
    }

    // ------------------------------------------------------------------
    // 解除引用后可改可删
    // ------------------------------------------------------------------

    @Test
    @DisplayName("disabling the agent releases the credential for rotate and disable")
    void disablingTheAgentReleasesTheCredential() throws Exception {
        UUID released = fx.createCredential("released-key", SECRET);
        UUID agentId = bindAgent("released-agent", released);
        UUID stillBound = fx.createCredential("still-bound-key", SECRET);
        bindAgent("still-bound-agent", stillBound);

        disableAgent(agentId);

        // The released credential rotates again; the other binding is unaffected.
        rotate(released).andExpect(status().isOk());
        Map<String, Object> active = row(
                "SELECT * FROM upstream_credential_versions WHERE credential_id = :id AND status = 'ACTIVE'",
                released);
        assertThat((byte[]) active.get("secret_fingerprint")).isEqualTo(CredentialFingerprint.sha256(SECRET_2));
        rotate(stillBound).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_REFERENCED_BY_AGENT"));

        // Disable follows the same rule.
        disable(stillBound).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_REFERENCED_BY_AGENT"));
        disable(released).andExpect(status().isOk());
        assertThat(row("SELECT * FROM upstream_credentials WHERE id = :id", released).get("status"))
                .isEqualTo("DISABLED");
        assertThat(actionsFor(released)).containsExactlyInAnyOrder("CREDENTIAL_CREATE", "CREDENTIAL_ROTATE",
                "CREDENTIAL_DISABLE");
    }

    @Test
    @DisplayName("a binding agent that was already disabled does not pin the credential")
    void anAlreadyDisabledAgentDoesNotPinTheCredential() throws Exception {
        UUID credentialId = fx.createCredential("prod-key", SECRET);
        UUID agentId = bindAgent("retired-agent", credentialId);
        disableAgent(agentId);
        // A disabled agent keeps its historical binding for usage attribution but is
        // no longer part of the reference set.
        assertThat(row("SELECT * FROM agents WHERE id = :id", agentId).get("status")).isEqualTo("DISABLED");

        disable(credentialId).andExpect(status().isOk());

        assertThat(row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId).get("status"))
                .isEqualTo("DISABLED");
    }

    // ------------------------------------------------------------------
    // 租户隔离与鉴权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a credential referenced in another tenant is not found, not 409")
    void aCredentialReferencedInAnotherTenantIsNotFound() throws Exception {
        UUID foreign = seedForeignTenantReferencedCredential();

        String rotateBody = rotate(foreign).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        disable(foreign).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));

        // A cross-tenant reference must read as "not found": the body may not name
        // the foreign agent or credential, which would confirm they exist elsewhere.
        assertThat(rotateBody).doesNotContain("foreign-agent").doesNotContain("foreign-key");

        // The foreign credential is untouched and still pinned by its own agent.
        assertThat(row("SELECT * FROM upstream_credentials WHERE id = :id", foreign).get("status")).isEqualTo("ACTIVE");
        assertThat(rows("SELECT * FROM upstream_credential_versions WHERE credential_id = :id", foreign)).hasSize(1);
    }

    @Test
    @DisplayName("anonymous and USER roles are denied before the reference check runs")
    void mutationStaysGatedForAnonymousAndUserRoles() throws Exception {
        UUID credentialId = fx.createCredential("prod-key", SECRET);
        bindAgent("客服助手", credentialId);

        mockMvc.perform(post("/api/v1/admin/credentials/{id}/rotate", credentialId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("secret", SECRET_2))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/admin/credentials/{id}/disable", credentialId))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = :t",
                new MapSqlParameterSource("t", fx.tenantId), UUID.class);
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = :id", new MapSqlParameterSource("id", adminId));

        rotate(credentialId).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        disable(credentialId).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(row("SELECT * FROM upstream_credentials WHERE id = :id", credentialId).get("status"))
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------
    // request helpers
    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions rotate(UUID credentialId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/credentials/{id}/rotate", credentialId)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("secret", SECRET_2))));
    }

    private org.springframework.test.web.servlet.ResultActions disable(UUID credentialId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/credentials/{id}/disable", credentialId).cookie(sessionCookie,
                csrfCookie).header("X-CSRF-Token", csrfToken));
    }

    private UUID bindAgent(String name, UUID credentialId) throws Exception {
        MvcResult result = mockMvc
                .perform(post("/api/v1/admin/agents").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"credentialId\":\"" + credentialId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString((String) objectMapper.readValue(result.getResponse().getContentAsString(), Map.class)
                .get("id"));
    }

    private void disableAgent(UUID agentId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/agents/{id}/disable", agentId).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // seeding / assertions
    // ------------------------------------------------------------------

    /** Another tenant whose credential is pinned by one of its own ACTIVE agents. */
    private UUID seedForeignTenantReferencedCredential() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version)
                VALUES (:id, :code, 'Foreign Tenant', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", tenantId).addValue("code", "t-" + tenantId.toString().substring(0, 8)));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                   must_change_password, failed_login_count, version)
                VALUES (:id, :tenantId, :username, 'Foreign Admin', :hash, 'SYSTEM_ADMIN', 'ACTIVE', FALSE, 0, 0)
                """, new MapSqlParameterSource("id", userId).addValue("tenantId", tenantId)
                .addValue("username", "foreign_" + userId.toString().substring(0, 8))
                .addValue("hash", new byte[]{0}));
        jdbc.update("""
                INSERT INTO upstream_subscriptions
                    (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                VALUES (:id, :tenantId, :productId, 'Foreign Sub', 'PAYG', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                .addValue("productId", fx.productId));
        jdbc.update("""
                INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                VALUES (:id, :tenantId, :subscriptionId, 'foreign-key', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                .addValue("subscriptionId", subscriptionId));
        jdbc.update("""
                INSERT INTO upstream_credential_versions
                    (id, tenant_id, credential_id, encrypted_secret, nonce, encryption_key_version,
                     secret_fingerprint, status, valid_from)
                VALUES (:id, :tenantId, :credentialId, :secret, :nonce, 'v1', :fingerprint, 'ACTIVE', now())
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                .addValue("credentialId", credentialId).addValue("secret", new byte[]{1, 2, 3})
                .addValue("nonce", new byte[]{4}).addValue("fingerprint", CredentialFingerprint.sha256(SECRET)));
        jdbc.update("""
                INSERT INTO agents
                    (id, tenant_id, name, upstream_credential_id, status, version, created_by)
                VALUES (:id, :tenantId, 'foreign-agent', :credentialId, 'ACTIVE', 0, :createdBy)
                """, new MapSqlParameterSource("id", agentId).addValue("tenantId", tenantId)
                .addValue("credentialId", credentialId).addValue("createdBy", userId));
        return credentialId;
    }

    private Map<String, Object> row(String sql, UUID id) {
        return jdbc.queryForMap(sql, new MapSqlParameterSource("id", id));
    }

    private List<Map<String, Object>> rows(String sql, UUID id) {
        return jdbc.queryForList(sql, new MapSqlParameterSource("id", id));
    }

    private List<String> actionsFor(UUID targetId) {
        return jdbc.queryForList("SELECT action FROM admin_audit_events WHERE target_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", targetId), String.class);
    }

    private static Cookie cookie(MvcResult result, String name) {
        if (result.getResponse().getCookies() == null) {
            return null;
        }
        return Arrays.stream(result.getResponse().getCookies()).filter(c -> name.equals(c.getName())).findFirst()
                .orElse(null);
    }

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("agents", "usage_event", "cache_hit_event", "price_snapshot",
                    "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                    "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                    "project_memberships", "project_repositories", "projects", "provider_products", "providers",
                    "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
            try {
                jdbc.update("DELETE FROM tenants WHERE id <> :seed",
                        new MapSqlParameterSource("seed", tenantId));
            } catch (Exception ignored) {
                // The seed tenant is never deleted.
            }
        }

        void insertCatalog() {
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
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
        }

        UUID createCredential(String name, String secret) throws Exception {
            MvcResult result = mockMvc
                    .perform(post("/api/v1/admin/credentials").contentType(MediaType.APPLICATION_JSON)
                            .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("name", name, "subscriptionId", subscriptionId, "secret", secret))))
                    .andExpect(status().isCreated()).andReturn();
            Map<?, ?> view = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
            return UUID.fromString((String) view.get("id"));
        }
    }

    static class BootstrapHelper {
        private static final java.nio.file.Path SECRET_FILE = java.nio.file.Path
                .of(System.getProperty("java.io.tmpdir"), "miqrokey-bootstrap-test");
        private static final String SECRET = "bootstrap-secret-for-g16-tests";

        static {
            try {
                java.nio.file.Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new IllegalStateException(e);
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
