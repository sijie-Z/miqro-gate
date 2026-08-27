package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
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

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization integration tests using real persisted users and sessions.
 *
 * <p>
 * Covers: SYSTEM_ADMIN access to /api/v1/admin/**, USER denial (403),
 * unauthenticated denial (401), and cross-user IDOR protection via
 * OwnershipService on /api/v1/test/ownership/{ownerUserId}.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Authorization integration tests (real users + sessions)")
class AuthorizationIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String secretPath = AuthIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString();
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> secretPath);
        // Lower lock threshold for faster login failure tests
        registry.add("miqrokey.login-max-failures", () -> "3");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        try {
            jdbc.update("DELETE FROM user_sessions", new MapSqlParameterSource());
            jdbc.update("DELETE FROM users", new MapSqlParameterSource());
            jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------------
    // Admin path access control (RoleInterceptor deny-by-default)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("SYSTEM_ADMIN can access /api/v1/admin/test")
    void adminCanAccessAdminPaths() throws Exception {
        PreparedSession ps = bootstrapAndPrepareSession("admin_auth");
        mockMvc.perform(get("/api/v1/admin/test").cookie(ps.session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("admin access granted"));
    }

    @Test
    @DisplayName("unauthenticated request to /api/v1/admin/ is denied with 401")
    void unauthenticatedAdminDenied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/test")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("USER receives 403 for /api/v1/admin/test")
    void userGets403ForAdminPath() throws Exception {
        PreparedSession ps = bootstrapAndPrepareSession("admin_403");
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = :id", new MapSqlParameterSource("id", ps.userId));

        mockMvc.perform(get("/api/v1/admin/test").cookie(ps.session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("admin test path returns RFC 9457 problem+json for unauthenticated")
    void adminPathProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/admin/test")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("about:blank")).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    // -------------------------------------------------------------------
    // Cross-user IDOR protection via OwnershipService
    // (endpoint outside /api/v1/admin/** so RoleInterceptor lets USER through)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("USER can access own protected resource (self access 200)")
    void userCanAccessOwnResource() throws Exception {
        PreparedSession ps = bootstrapAndPrepareSession("idor_self");
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = :id", new MapSqlParameterSource("id", ps.userId));

        mockMvc.perform(get("/api/v1/test/ownership/{ownerUserId}", ps.userId).cookie(ps.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("access granted"))
                .andExpect(jsonPath("$.currentUserId").value(ps.userId.toString()))
                .andExpect(jsonPath("$.resourceOwnerId").value(ps.userId.toString()));
    }

    @Test
    @DisplayName("USER receives 404 for another user's protected resource (IDOR denied, resource hiding)")
    void userDeniedCrossUserIdor() throws Exception {
        PreparedSession ps = bootstrapAndPrepareSession("idor_cross");
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = :id", new MapSqlParameterSource("id", ps.userId));

        UUID otherUserId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/test/ownership/{ownerUserId}", otherUserId).cookie(ps.session))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.detail").value("Resource not found."))
                .andExpect(jsonPath("$.requestId").value(notNullValue()));
    }

    @Test
    @DisplayName("SYSTEM_ADMIN can access any user's protected resource (admin override 200)")
    void adminCanAccessAnyUserResource() throws Exception {
        PreparedSession ps = bootstrapAndPrepareSession("admin_override");
        UUID otherUserId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/test/ownership/{ownerUserId}", otherUserId).cookie(ps.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("access granted"));
    }

    @Test
    @DisplayName("unauthenticated receives 401 for ownership endpoint")
    void unauthenticatedOwnershipDenied() throws Exception {
        mockMvc.perform(get("/api/v1/test/ownership/{ownerUserId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("SYSTEM_ADMIN can access admin user detail endpoint")
    void adminCanAccessUserDetail() throws Exception {
        PreparedSession ps = bootstrapAndPrepareSession("admin_detail");
        mockMvc.perform(get("/api/v1/admin/users/some-user-id").cookie(ps.session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("unauthenticated receives 401 for admin user detail")
    void unauthenticatedUserDetailDenied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/some-user-id")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Bootstrap an admin, then complete the mandatory password-change flow so the
     * session becomes eligible for authorization checks (mustChangePassword=false).
     * Returns the ready-to-use session cookie and the user's ID.
     */
    private PreparedSession bootstrapAndPrepareSession(String username) throws Exception {
        String uniqueUser = username + "_" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Bootstrap
        MvcResult bootR = mockMvc
                .perform(
                        post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                        AuthIntegrationTest.BootstrapHelper.secret(), uniqueUser, "Test"))))
                .andReturn();

        Cookie sessionCookie = extractCookie(bootR, "MIQROKEY_SESSION");
        Cookie csrfCookie = extractCookie(bootR, AuthIntegrationTest.DEFAULT_CSRF_NAME);
        String tempPassword = AuthIntegrationTest.BootstrapHelper.extractTemporaryPassword(objectMapper, bootR);

        // 2. Change password (requires CSRF cookie + X-CSRF-Token header)
        String csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        PasswordChangeRequest changeReq = new PasswordChangeRequest(tempPassword, "NewSecurePass1!");
        mockMvc.perform(
                post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(changeReq)))
                .andExpect(status().isOk());

        // 3. Login with new password
        MvcResult loginR = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(uniqueUser, "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();

        Cookie newSession = extractCookie(loginR, "MIQROKEY_SESSION");

        var body = objectMapper.readValue(loginR.getResponse().getContentAsString(), java.util.Map.class);
        UUID userId = UUID.fromString((String) body.get("id"));

        return new PreparedSession(newSession, userId);
    }

    private Cookie extractCookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies()) {
            if (name.equals(c.getName()))
                return c;
        }
        return null;
    }

    private record PreparedSession(Cookie session, UUID userId) {
    }
}
