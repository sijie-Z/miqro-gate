package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Open self-registration (F-REG): POST /api/v1/auth/register creates a USER
 * account and signs it in with the same session cookies as /login. Duplicate
 * usernames and weak passwords are rejected with stable codes; the new account
 * works immediately (the /me probe below uses the registration session).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Self-registration API integration tests (PostgreSQL)")
class RegistrationApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.registration-enabled", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        resetDb();
        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                        "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() {
        resetDb();
    }

    @Test
    @DisplayName("register creates a USER account, signs it in and the session works immediately")
    void registerAndSessionWorks() throws Exception {
        MvcResult r = mockMvc
                .perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"displayName\":\"新同学\",\"password\":\"StrongPass2026!\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("newbie"))
                .andExpect(jsonPath("$.role").value("USER")).andExpect(jsonPath("$.mustChangePassword").value(false))
                .andReturn();

        Cookie session = cookie(r, "MIQROKEY_SESSION");
        assertThat(session).isNotNull();
        // The registration session is immediately usable.
        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newbie")).andExpect(jsonPath("$.displayName").value("新同学"));

        Long users = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = 'newbie' AND role = 'USER' AND status = 'ACTIVE'",
                new MapSqlParameterSource(), Long.class);
        assertThat(users).isEqualTo(1L);
    }

    @Test
    @DisplayName("duplicate usernames and weak passwords are rejected with stable codes")
    void validation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newbie\",\"password\":\"StrongPass2026!\"}")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newbie\",\"password\":\"StrongPass2026!\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"weak\",\"password\":\"abc\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_INVALID"));
    }

    @Test
    @DisplayName("registration is public: no session and no CSRF token are required")
    void registerIsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"solo\",\"password\":\"StrongPass2026!\"}")).andExpect(status().isCreated());
    }

    private void resetDb() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "usage_event", "price_snapshot", "quota_rules", "quota_default_template", "virtual_key_models",
                "key_project_binding", "model_approval", "virtual_keys", "project_provider_grant_models",
                "project_provider_grants", "upstream_credential_versions", "upstream_credentials", "plan_seats",
                "upstream_subscriptions", "project_memberships", "projects", "provider_products", "providers",
                "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Child-first order above covers the canonical FK set.
            }
        }
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
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
