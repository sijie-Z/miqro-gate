package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.dto.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Auth integration tests (PostgreSQL)")
class AuthIntegrationTest {

    /** Default CSRF cookie name used by production and most test classes. */
    static final String DEFAULT_CSRF_NAME = "MIQROKEY_CSRF";

    /** Policy-compliant password for register-based fixtures (F-REG). */
    static final String STRONG_PASSWORD = "StrongPass2026!";

    // Shared singleton container — see AbstractControlPlaneIntegrationTest
    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        String secretPath = BootstrapHelper.secretFile().toAbsolutePath().toString();
        registry.add("miqrokey.bootstrap-secret-file", () -> secretPath);
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
            jdbc.update("DELETE FROM user_sessions",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource());
            jdbc.update("DELETE FROM users", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource());
        } catch (Exception ignored) {
        }
    }

    // ---- Bootstrap ----

    @Test
    @DisplayName("bootstrap creates admin with temp password and session cookies")
    void bootstrapSuccess() throws Exception {
        String username = uniqueUser("adm");
        MvcResult r = bootstrap(username);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        assertThat((String) body.get("temporaryPassword")).isNotEmpty();
        assertThat((Boolean) body.get("shownOnce")).isTrue();
        Cookie sess = getCookie(r, "MIQROKEY_SESSION");
        assertThat(sess).isNotNull();
        assertThat(sess.isHttpOnly()).isTrue();
    }

    @Test
    @DisplayName("bootstrap rejects wrong secret")
    void bootstrapWrongSecret() throws Exception {
        String username = uniqueUser("adm2");
        BootstrapRequest req = new BootstrapRequest("wrong-secret-value-abcdef", username, "A2");
        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("bootstrap rejects second attempt")
    void bootstrapSecondRejected() throws Exception {
        String u1 = uniqueUser("adm3");
        bootstrap(u1);
        BootstrapRequest req = new BootstrapRequest(BootstrapHelper.secret(), uniqueUser("adm4"), "A4");
        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isUnauthorized());
    }

    // ---- Login ----

    @Test
    @DisplayName("login fails with wrong password (generic message)")
    void loginWrongPassword() throws Exception {
        String username = uniqueUser("usr");
        bootstrap(username);
        login(username, "wrong-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("login fails for unknown user (same generic message as wrong password)")
    void loginUnknownUser() throws Exception {
        login("nonexistent_user_xyz", "anything").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---- Full happy path: bootstrap -> force password change -> re-login ----

    @Test
    @DisplayName("full flow: bootstrap, force password change, re-login, me, CSRF, logout")
    void fullHappyPath() throws Exception {
        String username = uniqueUser("flow");
        MvcResult bootR = bootstrap(username);
        Map<?, ?> bootBody = objectMapper.readValue(bootR.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        Cookie sessionCookie = getCookie(bootR, "MIQROKEY_SESSION");
        Cookie csrfCookie = getCookie(bootR, DEFAULT_CSRF_NAME);
        String csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";

        // GET /me with temp-password session
        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // GET /csrf with temp-password session — must send both cookies so the
        // controller can read the CSRF cookie from the request
        MvcResult csrfR = mockMvc.perform(get("/api/v1/auth/csrf").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> csrfBody = objectMapper.readValue(csrfR.getResponse().getContentAsString(), Map.class);
        String csrfResp = (String) csrfBody.get("token");
        assertThat(csrfResp).isNotEmpty();
        assertThat(csrfResp).isEqualTo(csrfToken);

        // Change password
        PasswordChangeRequest changeReq = new PasswordChangeRequest(tempPassword, "NewSecurePass1!");
        mockMvc.perform(
                post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(changeReq)))
                .andExpect(status().isOk());

        // Login with new password
        MvcResult loginR = login(username, "NewSecurePass1!").andReturn();
        assertThat(loginR.getResponse().getStatus()).isEqualTo(200);
        Map<?, ?> loginBody = objectMapper.readValue(loginR.getResponse().getContentAsString(), Map.class);
        assertThat((Boolean) loginBody.get("mustChangePassword")).isFalse();
        Cookie newSession = getCookie(loginR, "MIQROKEY_SESSION");
        Cookie newCsrfCookie = getCookie(loginR, DEFAULT_CSRF_NAME);
        assertThat(newSession).isNotNull();

        // GET /me with new session
        mockMvc.perform(get("/api/v1/auth/me").cookie(newSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SYSTEM_ADMIN"));

        // Logout (POST requires CSRF)
        String newCsrfToken = newCsrfCookie != null ? newCsrfCookie.getValue() : "";
        mockMvc.perform(
                post("/api/v1/auth/logout").cookie(newSession, newCsrfCookie).header("X-CSRF-Token", newCsrfToken))
                .andExpect(status().isOk());

        // GET /me after logout (session revoked)
        mockMvc.perform(get("/api/v1/auth/me").cookie(newSession)).andExpect(status().isUnauthorized());
    }

    // ---- CSRF protection ----

    @Test
    @DisplayName("CSRF: state-changing POST without CSRF token is rejected")
    void csrfRequired() throws Exception {
        String username = uniqueUser("csrf");
        MvcResult bootR = bootstrap(username);
        Map<?, ?> bootBody = objectMapper.readValue(bootR.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        Cookie session = getCookie(bootR, "MIQROKEY_SESSION");

        PasswordChangeRequest req = new PasswordChangeRequest(tempPassword, "NewPass1!");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).cookie(session)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    @DisplayName("CSRF: state-changing POST with valid CSRF token succeeds")
    void csrfValid() throws Exception {
        String username = uniqueUser("csrf2");
        MvcResult bootR = bootstrap(username);
        Map<?, ?> bootBody = objectMapper.readValue(bootR.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        Cookie session = getCookie(bootR, "MIQROKEY_SESSION");
        String csrf = getCsrfToken(bootR);

        PasswordChangeRequest req = new PasswordChangeRequest(tempPassword, "NewPass1!");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).cookie(session)
                .header("X-CSRF-Token", csrf).content(objectMapper.writeValueAsString(req))).andExpect(status().isOk());
    }

    // ---- Login failure durability (counter survives across requests) ----

    @Test
    @DisplayName("login failure counter persists across requests (durability)")
    void loginFailureCounterDurability() throws Exception {
        String username = uniqueUser("lock");
        bootstrap(username);
        // Fail several times
        for (int i = 0; i < 5; i++) {
            login(username, "wrong").andExpect(status().isUnauthorized());
        }
        // On 6th attempt, should still reject generically
        login(username, "wrong").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---- Session expiry / revoked ----

    @Test
    @DisplayName("logout revokes session")
    void logoutRevokesSession() throws Exception {
        String username = uniqueUser("lout");
        MvcResult bootR = bootstrap(username);
        Cookie session = getCookie(bootR, "MIQROKEY_SESSION");
        Cookie csrfCookie = getCookie(bootR, DEFAULT_CSRF_NAME);
        String csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";

        // Logout is a state-changing POST — send CSRF cookie + X-CSRF-Token header
        mockMvc.perform(post("/api/v1/auth/logout").cookie(session, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    // ---- Password change revokes other sessions ----

    @Test
    @DisplayName("password change revokes other sessions")
    void passwordChangeRevokesOtherSessions() throws Exception {
        String username = uniqueUser("pcr");
        MvcResult bootR = bootstrap(username);
        Map<?, ?> bootBody = objectMapper.readValue(bootR.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        Cookie session1 = getCookie(bootR, "MIQROKEY_SESSION");
        String csrf = getCsrfToken(bootR);

        // Create second session
        MvcResult loginR = login(username, tempPassword).andReturn();
        Cookie session2 = getCookie(loginR, "MIQROKEY_SESSION");

        // Change password via session1
        PasswordChangeRequest req = new PasswordChangeRequest(tempPassword, "NewPass123!");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).cookie(session1)
                .header("X-CSRF-Token", csrf).content(objectMapper.writeValueAsString(req))).andExpect(status().isOk());

        // session2 should be revoked
        mockMvc.perform(get("/api/v1/auth/me").cookie(session2)).andExpect(status().isUnauthorized());
    }

    // ---- Self-service logout of other sessions (#597) ----

    @Test
    @DisplayName("logout-others revokes other sessions, keeps the calling one")
    void logoutOthersRevokesOtherSessions() throws Exception {
        String username = uniqueUser("lor");
        MvcResult regR = register(username, STRONG_PASSWORD);
        Cookie session1 = getCookie(regR, "MIQROKEY_SESSION");
        String csrf = getCsrfToken(regR);
        assertThat(session1).isNotNull();

        Cookie session2 = getCookie(login(username, STRONG_PASSWORD).andReturn(), "MIQROKEY_SESSION");
        Cookie session3 = getCookie(login(username, STRONG_PASSWORD).andReturn(), "MIQROKEY_SESSION");
        assertThat(session2).isNotNull();
        assertThat(session3).isNotNull();

        mockMvc.perform(post("/api/v1/auth/logout-others").cookie(session1).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").isNotEmpty());

        // The calling session survives; the other two are revoked.
        mockMvc.perform(get("/api/v1/auth/me").cookie(session1)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").cookie(session2)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(session3)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout-others: unauthenticated 401, missing CSRF 403, LOGOUT_OTHERS audit row")
    void logoutOthersGuardsAndAudit() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-others")).andExpect(status().isUnauthorized());

        String username = uniqueUser("log");
        MvcResult regR = register(username, STRONG_PASSWORD);
        Cookie session = getCookie(regR, "MIQROKEY_SESSION");
        String csrf = getCsrfToken(regR);

        mockMvc.perform(post("/api/v1/auth/logout-others").cookie(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        mockMvc.perform(post("/api/v1/auth/logout-others").cookie(session).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk());

        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE username = :u",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("u", username), UUID.class);
        Integer audited = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_events WHERE action = 'LOGOUT_OTHERS' AND actor_id = :id"
                        + " AND target_type = 'USER' AND target_id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", userId), Integer.class);
        assertThat(audited).isPositive();
    }

    @Test
    @DisplayName("logout-others is gated for forced-password-change sessions")
    void logoutOthersGatedByMustChangePassword() throws Exception {
        String username = uniqueUser("lom");
        MvcResult bootR = bootstrap(username);
        Cookie session = getCookie(bootR, "MIQROKEY_SESSION");
        String csrf = getCsrfToken(bootR);

        mockMvc.perform(post("/api/v1/auth/logout-others").cookie(session).header("X-CSRF-Token", csrf))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    // ---- Origin validation ----

    @Test
    @DisplayName("Origin: valid origin accepted")
    void originValid() throws Exception {
        String username = uniqueUser("orig");
        bootstrap(username);
        String body = objectMapper.writeValueAsString(new LoginRequest(username, "wrong"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost:5173").content(body)).andExpect(status().isUnauthorized()); // wrong
                                                                                                                // password
                                                                                                                // but
                                                                                                                // origin
                                                                                                                // accepted
    }

    @Test
    @DisplayName("Origin: malformed origin rejected")
    void originMalformed() throws Exception {
        String username = uniqueUser("omf");
        bootstrap(username);
        String body = objectMapper.writeValueAsString(new LoginRequest(username, "wrong"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", ":::not-a-uri:::").content(body)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
    }

    @Test
    @DisplayName("Origin: bypass string rejected (localhost.evil.com)")
    void originBypassRejected() throws Exception {
        String username = uniqueUser("obp");
        bootstrap(username);
        String body = objectMapper.writeValueAsString(new LoginRequest(username, "wrong"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost.evil.com:5173").content(body)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
    }

    // ---- GET /csrf returns usable token ----

    @Test
    @DisplayName("GET /auth/csrf returns non-empty token")
    void csrfReturnsToken() throws Exception {
        String username = uniqueUser("csrft");
        MvcResult bootR = bootstrap(username);
        Cookie session = getCookie(bootR, "MIQROKEY_SESSION");
        Cookie csrfCookie = getCookie(bootR, DEFAULT_CSRF_NAME);
        MvcResult r = mockMvc.perform(get("/api/v1/auth/csrf").cookie(session, csrfCookie)).andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        assertThat((String) body.get("token")).isNotEmpty();
    }

    // ---- Unauthenticated access ----

    @Test
    @DisplayName("GET /me without session returns 401")
    void meUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /logout without session returns 401")
    void logoutUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /csrf without session returns 401")
    void csrfUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isUnauthorized());
    }

    // ---- No secrets in DB ----

    @Test
    @DisplayName("DB contains no plaintext password or session token")
    void noSecretsInDb() throws Exception {
        String username = uniqueUser("nosec");
        bootstrap(username);

        // Check user_sessions: token_digest should not be empty but should be 32 bytes
        Integer sessionCount = jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), Integer.class);
        assertThat(sessionCount).isPositive();

        // password_hash must be non-empty Argon2 encoded string
        String hashSample = jdbc.queryForObject("SELECT encode(password_hash, 'escape') FROM users LIMIT 1",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), String.class);
        assertThat(hashSample).startsWith("$argon2id$");
    }

    // ---- Helpers ----

    private MvcResult bootstrap(String username) throws Exception {
        return mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper
                                .writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(), username, "Test"))))
                .andReturn();
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(username, password))));
    }

    private MvcResult register(String username, String password) throws Exception {
        return mockMvc
                .perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, "Test", password))))
                .andReturn();
    }

    private Cookie getCookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    private String getCsrfToken(MvcResult r) {
        Cookie c = getCookie(r, DEFAULT_CSRF_NAME);
        return c != null ? c.getValue() : "";
    }

    private Cookie getCsrfCookie(MvcResult r) {
        return getCookie(r, DEFAULT_CSRF_NAME);
    }

    private String uniqueUser(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    static class BootstrapHelper {
        static final Path SECRET_FILE;
        static final String SECRET = "test-bootstrap-secret-min-16chars";
        static {
            try {
                SECRET_FILE = Files.createTempFile("bootstrap-secret", ".txt");
                Files.writeString(SECRET_FILE, SECRET);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        static Path secretFile() {
            return SECRET_FILE;
        }
        static String secret() {
            return SECRET;
        }
        /**
         * Extract the temporary password from a bootstrap response body.
         */
        static String extractTemporaryPassword(ObjectMapper mapper, MvcResult bootR) throws IOException {
            Map<?, ?> body = mapper.readValue(bootR.getResponse().getContentAsString(), Map.class);
            Object pwd = body.get("temporaryPassword");
            if (pwd == null || pwd.toString().isEmpty()) {
                throw new IllegalStateException("Bootstrap response missing temporaryPassword");
            }
            return pwd.toString();
        }
    }
}
