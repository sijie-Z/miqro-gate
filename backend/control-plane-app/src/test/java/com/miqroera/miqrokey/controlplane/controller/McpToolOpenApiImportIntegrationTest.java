package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17 OpenAPI batch tool import: operationId/path-derived snake_case names,
 * summary descriptions, per-item conflict skipping, unsupported-method notes
 * and the 100-tool cap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP tools OpenAPI import integration tests (PostgreSQL)")
class McpToolOpenApiImportIntegrationTest {

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
        for (String table : new String[]{"mcp_tool_revisions", "mcp_tools", "mcp_services", "user_sessions", "users",
                "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private ObjectNode op(String operationId, String summary) {
        ObjectNode op = objectMapper.createObjectNode();
        if (operationId != null) {
            op.put("operationId", operationId);
        }
        if (summary != null) {
            op.put("summary", summary);
        }
        return op;
    }

    private ObjectNode spec() {
        ObjectNode spec = objectMapper.createObjectNode();
        spec.put("openapi", "3.1.0");
        ObjectNode paths = spec.putObject("paths");
        ObjectNode orders = paths.putObject("/orders");
        orders.set("get", op("listOrders", "列出订单"));
        orders.set("post", op("createOrder", "创建订单"));
        ObjectNode orderItem = paths.putObject("/orders/{id}");
        orderItem.set("get", op(null, "查看订单"));
        ObjectNode admin = paths.putObject("/internal/admin");
        admin.set("patch", op("adminPatch", "管理补丁"));
        admin.set("trace", op("adminTrace", "不受支持的方法"));
        ObjectNode dup = paths.putObject("/duplicate");
        dup.set("get", op("query_order", "与已有工具重名"));
        return spec;
    }

    private void createExistingTool(String name) throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"" + name + "\",\"path\":\"/existing\"}")).andExpect(status().isOk());
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
    @DisplayName("imports derivable operations, reports conflicts and unsupported methods per item")
    void importBatch() throws Exception {
        createExistingTool("query_order");
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/import")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(spec())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created.length()").value(4))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].toolName").value("query_order"))
                .andExpect(jsonPath("$.parseSkips.length()").value(1))
                .andExpect(jsonPath("$.parseSkips[0].reason").value("HTTP 方法不支持：TRACE"));

        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.toolName == 'list_orders')].method").value("GET"))
                .andExpect(jsonPath("$[?(@.toolName == 'create_order')].path").value("/orders"))
                .andExpect(jsonPath("$[?(@.toolName == 'orders_id')].description").value("查看订单"))
                .andExpect(jsonPath("$[?(@.toolName == 'admin_patch')].method").value("PATCH"));
    }

    @Test
    @DisplayName("invalid specs are rejected before any insert")
    void invalidSpecRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/import")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"openapi\":\"3.1.0\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_INVALID"));
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("import endpoint requires a portal session")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/import")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
}
