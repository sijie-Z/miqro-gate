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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP tool definition versioning (F16, V33): publish-edit snapshots, the
 * activation pointer, parent-row mirroring for route snapshots and idempotent
 * rollback that never prunes or mints revision numbers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP tool revisions API integration tests (PostgreSQL)")
class McpToolRevisionApiIntegrationTest {

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

    private String createTool(String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"" + name + "\",\"description\":\"查询订单\",\"path\":\"/orders/{id}\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private void publish(String toolId, String json) throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
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
    @DisplayName("create seeds revision 1; publish snapshots the next revision and mirrors the parent")
    void publishSnapshotsAndMirrors() throws Exception {
        String toolId = createTool("query_order");
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .cookie(sessionCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revision").value(1)).andExpect(jsonPath("$[0].activatedAt").exists())
                .andExpect(jsonPath("$[0].changedFields", hasSize(0)));

        publish(toolId, "{\"description\":\"查询订单 v2\",\"method\":\"POST\"}");
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .cookie(sessionCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].revision").value(2)).andExpect(jsonPath("$[0].activatedAt").exists())
                .andExpect(jsonPath("$[0].changedFields", contains("description", "method")))
                .andExpect(jsonPath("$[1].revision").value(1)).andExpect(jsonPath("$[1].activatedAt").doesNotExist())
                .andExpect(jsonPath("$[1].changedFields", hasSize(0)));
        // Parent row mirrors the active revision for route-snapshot reads.
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].description").value("查询订单 v2"))
                .andExpect(jsonPath("$[0].method").value("POST"));
    }

    @Test
    @DisplayName("rollback moves the pointer, repeats are idempotent and later edits base on the active revision")
    void rollbackIsIdempotentPointerMove() throws Exception {
        String toolId = createTool("query_order");
        publish(toolId, "{\"description\":\"查询订单 v2\",\"method\":\"POST\",\"path\":\"/orders/v2/{id}\"}");

        // Rollback to revision 1: parent returns to the original definition.
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions/1/activate")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].description").value("查询订单"))
                .andExpect(jsonPath("$[0].method").value("GET")).andExpect(jsonPath("$[0].path").value("/orders/{id}"));

        // Re-activating the same revision is a no-op success (idempotent).
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions/1/activate")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .cookie(sessionCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        // A partial edit after rollback bases on the ACTIVE revision (rev 1), not rev
        // 2.
        publish(toolId, "{\"path\":\"/orders/fixed/{id}\"}");
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].description").value("查询订单"))
                .andExpect(jsonPath("$[0].method").value("GET"))
                .andExpect(jsonPath("$[0].path").value("/orders/fixed/{id}"));
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .cookie(sessionCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].revision").value(3)).andExpect(jsonPath("$[0].activatedAt").exists());
    }

    @Test
    @DisplayName("unknown tool, unknown revision and invalid input are rejected")
    void errorPaths() throws Exception {
        String toolId = createTool("query_order");
        String unknownTool = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + unknownTool + "/revisions")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"x\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TOOL_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions/99/activate")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOOL_REVISION_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PATCHX\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOOL_PATH_INVALID"));
    }

    @Test
    @DisplayName("revision endpoints require a portal session")
    void requiresAuth() throws Exception {
        String toolId = createTool("query_order");
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/tools/" + toolId + "/revisions"))
                .andExpect(status().isUnauthorized());
    }
}
