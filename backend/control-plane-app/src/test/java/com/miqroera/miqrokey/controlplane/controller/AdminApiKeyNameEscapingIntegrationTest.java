package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * rc.20 prepatch, #1230 M1: {@code AdminApiKeyService} built its audit summaries
 * with a local {@code safeJson} that escaped backslash and quote but left
 * control characters raw — a name with an internal line feed made the jsonb
 * cast reject the summary (PG 22P02, surfaced as 409 RESOURCE_CONFLICT) and
 * rolled the request back. All three sites (issue / scope update / revoke) now
 * go through the shared {@code AuditSummaries.escapeJson}; these tests pin the
 * literal round trip for control-char names and the negative control (quotes,
 * backslashes and CJK keep working end to end).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin API key name escaping in audit summaries (PostgreSQL)")
class AdminApiKeyNameEscapingIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
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
    @DisplayName("M1: names with line feed / tab are issued and round-trip through the audit summary")
    void controlCharsInNameIssueAndRoundTrip() throws Exception {
        for (String name : List.of("line\nbreak", "tab\tseparated")) {
            String id = issueKey(name);
            assertThat(id).isNotBlank();
            JsonNode summary = objectMapper.readTree(latestSummary("ADMIN_API_KEY_ISSUE"));
            assertThat(summary.size()).isEqualTo(1);
            assertThat(summary.get("name").asText()).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("M1: scope update on a control-char-named key is audited, not rejected")
    void controlCharNameSurvivesScopeUpdateAudit() throws Exception {
        String name = "scope\nkey";
        String id = issueKey(name);
        mockMvc.perform(patch("/api/v1/admin/api-keys/" + id + "/scope").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"capabilities\":[\"usage:read\"]}")).andExpect(status().isOk());

        JsonNode summary = objectMapper.readTree(latestSummary("ADMIN_API_KEY_SCOPE_UPDATE"));
        assertThat(summary.get("name").asText()).isEqualTo(name);
        assertThat(summary.get("to").get(0).asText()).isEqualTo("usage:read");
        assertThat(summary.get("from").isNull()).isTrue();
    }

    @Test
    @DisplayName("M1: revoke on a control-char-named key is audited, not rejected")
    void controlCharNameSurvivesRevokeAudit() throws Exception {
        String name = "revoke\nkey";
        String id = issueKey(name);
        mockMvc.perform(post("/api/v1/admin/api-keys/" + id + "/revoke").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        JsonNode summary = objectMapper.readTree(latestSummary("ADMIN_API_KEY_REVOKE"));
        assertThat(summary.get("name").asText()).isEqualTo(name);
    }

    @Test
    @DisplayName("negative control: quotes, backslashes and CJK in names keep working end to end")
    void legalNamesWithJsonSpecialsAndCjkRoundTrip() throws Exception {
        for (String name : List.of("q\"uote", "back\\slash", "中文密钥")) {
            String id = issueKey(name);
            JsonNode summary = objectMapper.readTree(latestSummary("ADMIN_API_KEY_ISSUE"));
            assertThat(summary.get("name").asText()).isEqualTo(name);
            mockMvc.perform(patch("/api/v1/admin/api-keys/" + id + "/scope").cookie(sessionCookie, csrfCookie)
                    .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"capabilities\":[\"usage:read\"]}")).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String issueKey(String name) throws Exception {
        MvcResult issued = mockMvc.perform(
                post("/api/v1/admin/api-keys").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> body = objectMapper.readValue(issued.getResponse().getContentAsString(), Map.class);
        return ((Map<?, ?>) body.get("key")).get("id").toString();
    }

    private String latestSummary(String action) {
        return jdbc.queryForObject("SELECT change_summary::text FROM admin_audit_events WHERE action = :action "
                + "ORDER BY chain_position DESC LIMIT 1", new MapSqlParameterSource("action", action), String.class);
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
