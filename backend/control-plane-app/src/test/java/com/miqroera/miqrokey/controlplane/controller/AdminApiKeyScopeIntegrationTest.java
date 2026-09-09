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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F60 batch 3 scope enforcement (ADR-0015 增补): a scoped machine key reaches
 * only its capability group; unscoped keys keep full access; scope changes are
 * audited and denied attempts are recorded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin API key scope integration tests (PostgreSQL)")
class AdminApiKeyScopeIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
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

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(AdminProviderApiIntegrationTest.BootstrapHelper.secret(),
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
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : List.of("export_tasks", "webhook_delivery_attempts", "alert_events", "alert_rules",
                "webhook_endpoints", "admin_api_keys", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("scope update narrows enforcement: usage group passes, alert/webhook/export are denied and audited")
    void scopedUsageKeyMatrix() throws Exception {
        String secret = issueKey("ops-usage");

        MvcResult patched = mockMvc
                .perform(patch("/api/v1/admin/api-keys/" + keyId(secret) + "/scope").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilities\":[\"usage:read\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capabilities[0]").value("usage:read")).andReturn();

        // In-scope read works.
        mockMvc.perform(
                get("/api/v1/admin-api/usage/summary?groupBy=project").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin-api/audit-events?size=1").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());

        // Out-of-scope writes and reads are denied with the scope code.
        for (String call : List.of(
                "POST|/api/v1/admin-api/alert-rules|{\"name\":\"x\",\"type\":\"USAGE_MISSING_RATE\",\"threshold\":0.1}",
                "POST|/api/v1/admin-api/webhooks|{\"name\":\"h\",\"url\":\"https://example.com/h\",\"secret\":\"s\"}",
                "GET|/api/v1/admin-api/alert-rules|",
                "GET|/api/v1/admin-api/virtual-keys?userId=00000000-0000-0000-0000-000000000009|")) {
            String[] parts = call.split("\\|", -1);
            var request = "POST".equals(parts[0])
                    ? post(parts[1]).contentType(MediaType.APPLICATION_JSON).content(parts[2])
                    : get(parts[1]);
            mockMvc.perform(request.header("Authorization", "Bearer " + secret)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ADMIN_API_SCOPE_DENIED"));
        }

        // Both the scope change and a denied attempt are audited.
        assertThat(countAudit("ADMIN_API_KEY_SCOPE_UPDATE")).isEqualTo(1);
        assertThat(countAudit("ADMIN_API_KEY_SCOPE_DENIED")).isGreaterThanOrEqualTo(1);

        // Clearing the scope (empty body) restores full access (NULL semantics).
        mockMvc.perform(patch("/api/v1/admin/api-keys/" + keyId(secret) + "/scope").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin-api/alert-rules").header("Authorization", "Bearer " + secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"type\":\"USAGE_MISSING_RATE\",\"threshold\":0.1}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("scope validation rejects unknown or duplicate capabilities")
    void scopeValidation() throws Exception {
        String secret = issueKey("ops");
        String id = keyId(secret);
        mockMvc.perform(patch("/api/v1/admin/api-keys/" + id + "/scope").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"capabilities\":[\"usage:read\",\"unknown:cap\"]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_API_KEY_SCOPE_INVALID"));
        mockMvc.perform(patch("/api/v1/admin/api-keys/" + id + "/scope").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"capabilities\":[\"usage:read\",\"usage:read\"]}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unscoped keys keep the full surface (backwards compatible)")
    void unscopedKeyFullAccess() throws Exception {
        String secret = issueKey("full");
        mockMvc.perform(
                get("/api/v1/admin-api/usage/summary?groupBy=project").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin-api/alert-rules").header("Authorization", "Bearer " + secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"type\":\"USAGE_MISSING_RATE\",\"threshold\":0.1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/admin-api/export-tasks?limit=5").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());
        assertThat(countAudit("ADMIN_API_KEY_SCOPE_DENIED")).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String issueKey(String name) throws Exception {
        MvcResult issued = mockMvc.perform(
                post("/api/v1/admin/api-keys").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> body = objectMapper.readValue(issued.getResponse().getContentAsString(), Map.class);
        String secret = String.valueOf(body.get("secret"));
        assertThat(secret).startsWith("mqk_admin_");
        return secret;
    }

    private String keyId(String secret) {
        return jdbc.queryForObject("SELECT id::text FROM admin_api_keys WHERE key_prefix = left(:secret, 18)",
                new MapSqlParameterSource("secret", secret), String.class);
    }

    private int countAudit(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Integer.class);
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
}
