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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Open admin write surface (ADR-0016 batch 2, A+C): alert rules and webhook
 * endpoints open as-is (option C - no executor columns), export creation is
 * delegated to the issuing admin (option A) and lands in created_by.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Open admin write surface integration tests (PostgreSQL)")
class OpenAdminWriteApiIntegrationTest {

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
    private UUID adminUserId;
    private String machineSecret;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        String adminUsername = "adm_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), adminUsername, "Admin"))))
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
        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername), UUID.class);

        // Issue a real machine key through the session API and capture the
        // one-time secret - the exact path a delegating admin would use.
        MvcResult issued = mockMvc.perform(
                post("/api/v1/admin/api-keys").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ops-write\"}"))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> issuedBody = objectMapper.readValue(issued.getResponse().getContentAsString(), Map.class);
        machineSecret = String.valueOf(issuedBody.get("secret"));
        assertThat(machineSecret).startsWith("mqk_admin_");
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

    @Test
    @DisplayName("machine key creates/updates/deletes alert rules and webhook endpoints")
    void ruleAndWebhookLifecycle() throws Exception {
        // Alert rule create -> list -> patch -> delete.
        MvcResult rule = mockMvc
                .perform(post("/api/v1/admin-api/alert-rules").header("Authorization", "Bearer " + machineSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "用量缺失率", "type", "USAGE_MISSING_RATE",
                                "threshold", 0.05, "dedupeMinutes", 30))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("USAGE_MISSING_RATE")).andReturn();
        String ruleId = objectMapper.readValue(rule.getResponse().getContentAsString(), Map.class).get("id").toString();
        mockMvc.perform(get("/api/v1/admin-api/alert-rules").header("Authorization", "Bearer " + machineSecret))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(
                patch("/api/v1/admin-api/alert-rules/" + ruleId).header("Authorization", "Bearer " + machineSecret)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"用量缺失率(改)\",\"threshold\":0.1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("用量缺失率(改)"));
        mockMvc.perform(
                delete("/api/v1/admin-api/alert-rules/" + ruleId).header("Authorization", "Bearer " + machineSecret))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/admin-api/alert-rules").header("Authorization", "Bearer " + machineSecret))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // Webhook endpoint create -> test target visible -> patch -> delete.
        mockMvc.perform(post("/api/v1/admin-api/webhooks").header("Authorization", "Bearer " + machineSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "ops-alerts", "url",
                        "https://example.com/miqro-hook", "secret", "whsec-test", "timeoutMs", 5000))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("ops-alerts"));
        mockMvc.perform(get("/api/v1/admin-api/webhooks").header("Authorization", "Bearer " + machineSecret))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("ops-alerts"));
        String endpointId = jdbc.queryForObject("SELECT id FROM webhook_endpoints WHERE name = 'ops-alerts'",
                new MapSqlParameterSource(), UUID.class).toString();
        mockMvc.perform(
                patch("/api/v1/admin-api/webhooks/" + endpointId).header("Authorization", "Bearer " + machineSecret)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(
                delete("/api/v1/admin-api/webhooks/" + endpointId).header("Authorization", "Bearer " + machineSecret))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("machine-created export tasks are delegated to the issuing admin in created_by")
    void exportCreationDelegatesToIssuer() throws Exception {
        String from = "2026-07-01T00:00:00Z";
        String to = "2026-07-02T00:00:00Z";
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin-api/export-tasks").param("format", "CSV").param("from", from)
                        .param("to", to).header("Authorization", "Bearer " + machineSecret))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        Map<?, ?> task = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        assertThat(task.get("createdBy").toString()).isEqualTo(adminUserId.toString());
        // created_by stays the issuing admin even when the file lands.
        String taskId = task.get("id").toString();
        mockMvc.perform(
                get("/api/v1/admin-api/export-tasks/" + taskId).header("Authorization", "Bearer " + machineSecret))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(taskId));
        String dbCreatedBy = jdbc.queryForObject("SELECT created_by FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(taskId)), UUID.class).toString();
        assertThat(dbCreatedBy).isEqualTo(adminUserId.toString());
    }

    @Test
    @DisplayName("write endpoints still require a valid machine credential")
    void writeRequiresCredential() throws Exception {
        mockMvc.perform(post("/api/v1/admin-api/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"type\":\"USAGE_MISSING_RATE\",\"threshold\":0.1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin-api/webhooks")
                .header("Authorization", "Bearer mqk_admin_revoked-or-unknown").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"url\":\"https://example.com/h\",\"secret\":\"s\"}"))
                .andExpect(status().isUnauthorized());
    }
}
