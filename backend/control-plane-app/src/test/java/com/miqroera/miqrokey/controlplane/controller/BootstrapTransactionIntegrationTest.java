package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PostgreSQL integration regression test proving that bootstrap completes
 * within a bounded timeout, commits exactly one user/session and a BOOTSTRAP
 * audit event, and does not leave partial data.
 *
 * <p>
 * This test was added to catch transaction self-deadlocks like the one caused
 * by {@code REQUIRES_NEW} audit propagation inside a bootstrap transaction that
 * held a tenant FOR UPDATE lock.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Bootstrap transaction integration tests")
class BootstrapTransactionIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        String secretPath = AuthIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString();
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
            jdbc.update("DELETE FROM user_sessions", new MapSqlParameterSource());
            jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
            jdbc.update("DELETE FROM users", new MapSqlParameterSource());
        } catch (Exception ignored) {
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("bootstrap completes within bounded timeout and commits exactly one user, session, and BOOTSTRAP audit event")
    void bootstrapCommitsAtomically() throws Exception {
        String username = "btx_admin_" + System.currentTimeMillis();

        MvcResult result = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AuthIntegrationTest.BootstrapHelper.secret(), username, "BTX Admin"))))
                .andExpect(status().isCreated()).andReturn();

        // Verify the response payload — BootstrapResponse is flat
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("username").asText()).isEqualTo(username);
        assertThat(body.get("temporaryPassword").asText()).isNotEmpty();
        assertThat(body.get("shownOnce").asBoolean()).isTrue();
        assertThat(body.get("sessionExpiresAt")).isNotNull();

        // Session token is delivered only as a cookie, never in the response body
        assertThat(body.has("tokens")).as("session token must NOT appear in body").isFalse();

        // Verify the MIQROKEY_SESSION cookie is present and HttpOnly
        Cookie sessionCookie = getCookie(result, "MIQROKEY_SESSION");
        assertThat(sessionCookie).as("MIQROKEY_SESSION cookie must be present").isNotNull();
        assertThat(sessionCookie.isHttpOnly()).as("MIQROKEY_SESSION cookie must be HttpOnly").isTrue();

        // Exactly one user committed
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users", new MapSqlParameterSource(),
                Integer.class);
        assertThat(userCount).as("exactly one user committed").isEqualTo(1);

        // Exactly one session committed
        Integer sessionCount = jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions", new MapSqlParameterSource(),
                Integer.class);
        assertThat(sessionCount).as("exactly one session committed").isEqualTo(1);

        // Exactly one BOOTSTRAP audit event committed
        Integer auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_events WHERE action = 'BOOTSTRAP'",
                new MapSqlParameterSource(), Integer.class);
        assertThat(auditCount).as("exactly one BOOTSTRAP audit event committed").isEqualTo(1);

        // No orphaned sessions (user_id must reference an existing user)
        Integer orphanedSessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_sessions s LEFT JOIN users u ON s.user_id = u.id WHERE u.id IS NULL",
                new MapSqlParameterSource(), Integer.class);
        assertThat(orphanedSessions).as("no orphaned sessions").isEqualTo(0);

        // The user has the expected admin role
        String role = jdbc.queryForObject("SELECT role FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), String.class);
        assertThat(role).isEqualTo("SYSTEM_ADMIN");

        // The user must be ACTIVE with must_change_password = true
        String status = jdbc.queryForObject("SELECT status FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), String.class);
        assertThat(status).isEqualTo("ACTIVE");

        Boolean mustChange = jdbc.queryForObject("SELECT must_change_password FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username), Boolean.class);
        assertThat(mustChange).as("bootstrap user must change password").isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("bootstrap with wrong secret does not leave any user/session/audit data")
    void bootstrapWrongSecretLeavesNoTrace() throws Exception {
        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper
                        .writeValueAsString(new BootstrapRequest("wrong-secret-value-here", "ghost_admin", "Ghost"))))
                .andExpect(status().isUnauthorized());

        // No user committed
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users", new MapSqlParameterSource(),
                Integer.class);
        assertThat(userCount).as("no user created with wrong secret").isEqualTo(0);

        // No session committed
        Integer sessionCount = jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions", new MapSqlParameterSource(),
                Integer.class);
        assertThat(sessionCount).as("no session created with wrong secret").isEqualTo(0);

        // No audit event
        Integer auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_events", new MapSqlParameterSource(),
                Integer.class);
        assertThat(auditCount).as("no audit event created with wrong secret").isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("second bootstrap after successful first is rejected, no duplicate data")
    void secondBootstrapRejectedNoDuplicates() throws Exception {
        String username1 = "btx_a_" + System.currentTimeMillis();

        // First bootstrap succeeds
        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new BootstrapRequest(AuthIntegrationTest.BootstrapHelper.secret(), username1, "First"))))
                .andExpect(status().isCreated());

        // Second bootstrap with different username must be rejected (same contract as
        // AuthIntegrationTest)
        String username2 = "btx_b_" + System.currentTimeMillis();
        mockMvc.perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new BootstrapRequest(AuthIntegrationTest.BootstrapHelper.secret(), username2, "Second"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // Still exactly one user
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users", new MapSqlParameterSource(),
                Integer.class);
        assertThat(userCount).as("still exactly one user after second bootstrap rejected").isEqualTo(1);

        // Still exactly one session
        Integer sessionCount = jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions", new MapSqlParameterSource(),
                Integer.class);
        assertThat(sessionCount).as("still exactly one session after second bootstrap rejected").isEqualTo(1);

        // Still exactly one BOOTSTRAP audit event
        Integer auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_events WHERE action = 'BOOTSTRAP'",
                new MapSqlParameterSource(), Integer.class);
        assertThat(auditCount).as("still exactly one BOOTSTRAP audit event").isEqualTo(1);

        // The first user is still there
        String foundUser = jdbc.queryForObject("SELECT username FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username1), String.class);
        assertThat(foundUser).isEqualTo(username1);

        // The second user was never created
        Integer secondUserCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = :u",
                new MapSqlParameterSource("u", username2), Integer.class);
        assertThat(secondUserCount).isEqualTo(0);
    }

    private Cookie getCookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }
}
