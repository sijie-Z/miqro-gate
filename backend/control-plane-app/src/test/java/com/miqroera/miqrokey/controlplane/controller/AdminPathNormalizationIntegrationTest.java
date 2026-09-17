package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for #723: path-based security decisions must see the same
 * normalized path the handler matcher routed on. Before the fix, a semicolon
 * path parameter ({@code /api/v1/admin;x/users}) reached the admin handler
 * while the deny-by-default gate compared the raw URI and skipped the role
 * check — live-proven against the demo deployment.
 *
 * <p>
 * The success-path assertions (admin reaches the endpoint, plain path
 * unchanged) also pin that the normalization does not over-block. Lifecycle is
 * per-class: bootstrap succeeds once per database, so the admin session is
 * created once and each test provisions its own USER.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Admin path normalization gate (#723, PostgreSQL)")
class AdminPathNormalizationIntegrationTest {

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

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private Cookie userSession;
    private Cookie userCsrf;
    private String userCsrfToken;

    @BeforeAll
    void bootstrapAdmin() throws Exception {
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), "adm_" + shortId(), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @BeforeEach
    void createUserSession() throws Exception {
        String username = "usr_" + shortId();
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "displayName", "Normal User", "role", "USER"))))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> createdBody = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        String temp = createdBody.get("temporaryPassword").toString();

        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, temp))))
                .andExpect(status().isOk()).andReturn();
        userSession = cookie(login, "MIQROKEY_SESSION");
        userCsrf = cookie(login, "MIQROKEY_CSRF");
        userCsrfToken = userCsrf != null ? userCsrf.getValue() : "";
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(userSession, userCsrf).header("X-CSRF-Token", userCsrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(temp, "UserSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a USER session cannot read admin endpoints through a semicolon path parameter")
    void userCannotReadAdminThroughSemicolonPath() throws Exception {
        // Control: the plain path is denied as before.
        mockMvc.perform(get("/api/v1/admin/users").cookie(userSession)).andExpect(status().isForbidden());
        // The regression: the semicolon variant reached the handler.
        mockMvc.perform(get("/api/v1/admin;x/users").cookie(userSession)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a USER session cannot write through a semicolon path parameter (no row created)")
    void userCannotWriteThroughSemicolonPath() throws Exception {
        String teamName = "bypass-probe-" + shortId();
        mockMvc.perform(post("/api/v1/admin;x/teams").contentType(MediaType.APPLICATION_JSON)
                .cookie(userSession, userCsrf).header("X-CSRF-Token", userCsrfToken)
                .content(objectMapper.writeValueAsString(Map.of("name", teamName)))).andExpect(status().isForbidden());

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM teams WHERE name = :name",
                new MapSqlParameterSource("name", teamName), Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("an encoded semicolon can never deliver admin data (gate or no-route)")
    void userCannotBypassThroughEncodedSemicolon() throws Exception {
        // MockMvc's router resolves %3B to no handler (404); a container that
        // decodes it into a routable path hits the normalized gate (403). Either
        // way the USER never receives admin data — assert the whole 4xx class.
        mockMvc.perform(get("/api/v1/admin%3Bx/users").cookie(userSession)).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("an anonymous request on the semicolon variant stays unauthorized")
    void anonymousSemicolonPathIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin;x/users")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the SYSTEM_ADMIN still reaches the endpoint through the same normalized path")
    void adminStillReachesAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin;x/users").cookie(adminSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/users").cookie(adminSession)).andExpect(status().isOk());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** First cookie with the given name, or null. */
    private static Cookie cookie(MvcResult result, String name) {
        for (Cookie c : result.getResponse().getCookies()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
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
