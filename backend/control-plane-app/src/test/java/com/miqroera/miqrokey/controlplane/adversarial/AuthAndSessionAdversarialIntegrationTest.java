package com.miqroera.miqrokey.controlplane.adversarial;

import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authentication surface, in both directions.
 *
 * <p>
 * Negative half: management endpoints must answer 401/403 — never 200 — for a
 * caller with no cookie, a forged cookie, a logged-out session, or a valid
 * low-privilege session. The check runs against a client that carries no
 * cookies at all, because a session-carrying client can mask a missing gate.
 * </p>
 *
 * <p>
 * Positive half (the session-establishment seam): a cookie handed out by
 * {@code /auth/login} must actually work on the next authenticated request —
 * {@code login → /auth/me → 200} and {@code login → POST /me/virtual-keys →
 * 201}. A session that cannot pass its own follow-up request is the failure
 * shape this half exists to catch.
 * </p>
 */
@Tag("integration")
@DisplayName("Adversarial: auth surface (401/403 on every rejection path) and session usability (PostgreSQL)")
class AuthAndSessionAdversarialIntegrationTest extends AbstractAdversarialIntegrationTest {

    private String adminUsername;
    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;

    @BeforeEach
    void setUp() throws Exception {
        adminUsername = "adm_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(AdversarialTestSupport.secret(), adminUsername, "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        String tempPassword = map(boot).get("temporaryPassword").toString();
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // positive half: the session must be usable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cookie from /auth/login passes /auth/me and can create a virtual key (201)")
    void loginSessionWorksOnSubsequentAuthenticatedRequests() throws Exception {
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(adminUsername, "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        Cookie session = cookie(login, "MIQROKEY_SESSION");
        Cookie csrf = cookie(login, "MIQROKEY_CSRF");
        assertThat(session).as("login hands out a session cookie").isNotNull();
        assertThat(csrf).as("login hands out a CSRF cookie").isNotNull();

        // The plainest possible follow-up: an authenticated read.
        MvcResult me = mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(body(me).get("username").asText()).isEqualTo(adminUsername);
        assertThat(body(me).get("role").asText()).isEqualTo("SYSTEM_ADMIN");

        // A state-changing request with the same cookie must be accepted (201),
        // not answered 401: this is the "expected 201, got 401" seam.
        Fixture fx = new Fixture();
        fx.seedGrantChain();
        MvcResult createdKey = mockMvc
                .perform(post("/api/v1/me/virtual-keys").cookie(session, csrf).header("X-CSRF-Token", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "adv-key", "projectId",
                                fx.projectId.toString(), "providerProductId", fx.productId.toString(),
                                "credentialGrantId", fx.grantId.toString(), "purpose", "CLAUDE_CODE"))))
                .andExpect(status().isCreated()).andReturn();
        String keyId = body(createdKey).get("id").asText();
        assertThat(keyId).isNotBlank();

        MvcResult listed = mockMvc.perform(get("/api/v1/me/virtual-keys").cookie(session)).andExpect(status().isOk())
                .andReturn();
        boolean found = false;
        for (tools.jackson.databind.JsonNode node : body(listed)) {
            found |= keyId.equals(node.get("id").asText());
        }
        assertThat(found).as("the created key is visible to its own session").isTrue();
    }

    @Test
    @DisplayName("positive control: the same admin endpoint answers 200 for a real admin session")
    void adminSessionIsAcceptedOnAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").cookie(adminSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/usage/summary").cookie(adminSession)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // negative half: none of the four shapes may reach a 200
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no cookie at all: management endpoints answer 401 (POSTs 401/403), never 200")
    void anonymousClientIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/usage/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/alert-rules")).andExpect(status().isUnauthorized());
        // State-changing paths may be refused by the CSRF gate first; either way
        // the answer is a rejection, never an acceptance.
        int status = mockMvc
                .perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"BUDGET_THRESHOLD\",\"threshold\":1}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).as("anonymous POST is rejected, not accepted").isIn(401, 403);
    }

    @Test
    @DisplayName("a forged session cookie is rejected with 401")
    void forgedCookieIsRejected() throws Exception {
        Cookie forged = new Cookie("MIQROKEY_SESSION", "deadbeefdeadbeefdeadbeefdeadbeef");
        mockMvc.perform(get("/api/v1/admin/users").cookie(forged)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(forged)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a logged-out session cookie stops working: 401 on the very next request")
    void loggedOutSessionIsRejected() throws Exception {
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(adminUsername, "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        Cookie session = cookie(login, "MIQROKEY_SESSION");
        Cookie csrf = cookie(login, "MIQROKEY_CSRF");
        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout").cookie(session, csrf).header("X-CSRF-Token", csrf.getValue()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/users").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a valid USER-role session is 403 on admin endpoints and 200 on /auth/me")
    void lowPrivilegeRoleIsForbiddenNotAccepted() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                   must_change_password, version)
                VALUES (:id, :tenantId, 'adv_plain_user', 'Plain User', :hash, 'USER', 'ACTIVE', FALSE, 0)
                """,
                new MapSqlParameterSource("id", userId)
                        .addValue("tenantId", UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .addValue("hash", passwordHasher.hash("NewSecurePass1!")));
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                        objectMapper.writeValueAsString(new LoginRequest("adv_plain_user", "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        Cookie session = cookie(login, "MIQROKEY_SESSION");
        Cookie csrf = cookie(login, "MIQROKEY_CSRF");

        // The session itself is good — so the 403s below are the role gate, not a
        // broken login.
        MvcResult me = mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(body(me).get("role").asText()).isEqualTo("USER");

        mockMvc.perform(get("/api/v1/admin/users").cookie(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/usage/summary").cookie(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/skills").cookie(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/alert-rules").cookie(session, csrf).header("X-CSRF-Token", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"type\":\"BUDGET_THRESHOLD\"," + "\"threshold\":1}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();

        void seedGrantChain() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'adv-auth-provider', 'Adv Auth Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:id, :providerId, 'adv-auth-product', 'Adv Auth Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.adv.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("id", productId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:id, :tenantId, 'ADVAUTH', 'Adv Auth', 'ACTIVE', 'adv-auth', 0)
                    """, new MapSqlParameterSource("id", projectId).addValue("tenantId", tenantId));
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
                    VALUES (:id, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE',
                            '00000000-0000-0000-0000-000000000000', 0)
                    """,
                    new MapSqlParameterSource("id", grantId).addValue("tenantId", tenantId)
                            .addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId));
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, 'model-alpha')
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId));
        }
    }
}
