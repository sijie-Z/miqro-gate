package com.miqroera.miqrokey.controlplane.controller;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP tools management (P3.5): tools under an MCP service with individual
 * enable/disable, name/path validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin MCP tools API integration tests (PostgreSQL)")
class AdminMcpToolApiIntegrationTest {

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
    private UUID serviceId;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), "root", "Admin"))))
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
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"erp-mcp\",\"endpoint\":\"https://erp.internal.example\"}"))
                .andExpect(status().isOk()).andReturn();
        serviceId = UUID.fromString(
                objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : new String[]{"mcp_tools", "mcp_services", "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("tool endpoints require authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("tool lifecycle: create with defaults, list, enable/disable")
    void toolLifecycle() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"query_order\",\"description\":\"查询订单\",\"path\":\"/orders/{id}\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.toolName").value("query_order"))
                .andExpect(jsonPath("$.method").value("GET")).andExpect(jsonPath("$.path").value("/orders/{id}"))
                .andExpect(jsonPath("$.status").value("ENABLED")).andReturn();
        String toolId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

        // Disable, then enable; duplicate switch conflicts.
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/status?status=DISABLED")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/status?status=DISABLED")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOOL_STATUS_UNCHANGED"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/status?status=ENABLED")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    @DisplayName("tool creation validates name, path, method and service scope")
    void toolValidation() throws Exception {
        // Invalid name (uppercase), invalid path, invalid method.
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"QueryOrder\",\"path\":\"/orders\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOOL_NAME_INVALID"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"query_order\",\"path\":\"orders\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOOL_PATH_INVALID"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"query_order\",\"method\":\"HEAD\",\"path\":\"/orders\"}"))
                .andExpect(status().isBadRequest());

        // Unknown service -> 404.
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + UUID.randomUUID() + "/tools")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"toolName\":\"query_order\",\"path\":\"/orders\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MCP_SERVICE_NOT_FOUND"));

        // Duplicate tool name within the service.
        String body = "{\"toolName\":\"query_order\",\"path\":\"/orders\"}";
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOOL_NAME_TAKEN"));
    }

    private static Cookie cookie(MvcResult result, String name) {
        return java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
    }
}
