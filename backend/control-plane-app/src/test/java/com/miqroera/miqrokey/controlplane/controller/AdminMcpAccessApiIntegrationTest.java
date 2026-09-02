package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.McpAccessView;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two-level MCP access control (Tencent doc 134890): server mode + lists and
 * per-tool overrides, with the upstream mode constraints and audit trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP access control API integration tests (PostgreSQL)")
class AdminMcpAccessApiIntegrationTest {

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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID serviceId;
    private UUID consumerA;
    private UUID consumerB;
    private UUID toolId;

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
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        serviceId = UUID
                .fromString((String) objectMapper
                        .readValue(mockMvc
                                .perform(post("/api/v1/admin/mcp-services").contentType(MediaType.APPLICATION_JSON)
                                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                                        .content(objectMapper.writeValueAsString(
                                                Map.of("name", "erp-mcp", "endpoint", "https://erp.internal.example"))))
                                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Map.class)
                        .get("id"));
        consumerA = createConsumer("consumer-a");
        consumerB = createConsumer("consumer-b");
        toolId = UUID
                .fromString(
                        (String) objectMapper
                                .readValue(
                                        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/tools")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                                                .content(objectMapper.writeValueAsString(
                                                        Map.of("toolName", "query_order", "path", "/orders"))))
                                                .andExpect(status().isOk()).andReturn().getResponse()
                                                .getContentAsString(),
                                        Map.class)
                                .get("id"));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : List.of("mcp_access_grants", "mcp_service_access", "mcp_tools", "mcp_services",
                "api_consumers", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Child-first order above covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("server mode ALLOW admits only listed consumers; replacing and clearing the list works")
    void serverAllowLifecycle() throws Exception {
        // Default: open server with no list.
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/access").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("NONE"))
                .andExpect(jsonPath("$.serverConsumers.length()").value(0));

        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/mode", Map.of("mode", "ALLOW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("ALLOW"));
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("mode", "ALLOW", "consumerIds", List.of(consumerA.toString(), consumerB.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serverConsumers.length()").value(2))
                .andExpect(jsonPath("$.serverConsumers[0].name").isNotEmpty());

        // Replace with one consumer.
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("mode", "ALLOW", "consumerIds", List.of(consumerB.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.serverConsumers.length()").value(1))
                .andExpect(jsonPath("$.serverConsumers[0].id").value(consumerB.toString()));

        // Clearing the server list returns the server to NONE (open).
        mockMvc.perform(delete("/api/v1/admin/mcp-services/" + serviceId + "/access/grants").cookie(sessionCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("NONE")).andExpect(jsonPath("$.serverConsumers.length()").value(0));
    }

    @Test
    @DisplayName("DENY blacklists consumers at the server level")
    void serverDeny() throws Exception {
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/mode", Map.of("mode", "DENY"))
                .andExpect(status().isOk());
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("mode", "DENY", "consumerIds", List.of(consumerA.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.serverConsumers.length()").value(1))
                .andExpect(jsonPath("$.serverConsumers[0].id").value(consumerA.toString()));
    }

    @Test
    @DisplayName("tool overrides exist only on an open server and are visible in the view")
    void toolOverrideLifecycle() throws Exception {
        // Second tool without any override shows inherit (mode null).
        mockMvc.perform(
                post("/api/v1/admin/mcp-services/" + serviceId + "/tools").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper
                                .writeValueAsString(Map.of("toolName", "query_invoice", "path", "/invoices"))))
                .andExpect(status().isOk());
        String firstView = mockMvc
                .perform(get("/api/v1/admin/mcp-services/" + serviceId + "/access").cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(toolAccess(firstView, "query_order").mode()).isNull();

        // Grant only consumer-a on query_order.
        mockMvc.perform(put("/api/v1/admin/mcp-services/" + serviceId + "/access/grants")
                .contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(Map.of("toolId",
                        toolId.toString(), "mode", "ALLOW", "consumerIds", List.of(consumerA.toString())))))
                .andExpect(status().isOk());

        String grantedView = mockMvc
                .perform(get("/api/v1/admin/mcp-services/" + serviceId + "/access").cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(toolAccess(grantedView, "query_order").mode().name()).isEqualTo("ALLOW");
        assertThat(toolAccess(grantedView, "query_order").consumers().get(0).id()).isEqualTo(consumerA);
        // The unconfigured tool still inherits.
        assertThat(toolAccess(grantedView, "query_invoice").mode()).isNull();

        // Clearing the override returns the tool to inherit.
        mockMvc.perform(delete("/api/v1/admin/mcp-services/" + serviceId + "/access/grants")
                .param("toolId", toolId.toString()).cookie(sessionCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk());
        String clearedView = mockMvc
                .perform(get("/api/v1/admin/mcp-services/" + serviceId + "/access").cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(toolAccess(clearedView, "query_order").mode()).isNull();
        assertThat(toolAccess(clearedView, "query_order").consumers()).isEmpty();
    }

    private static McpAccessView.ToolAccess toolAccess(String body, String toolName) throws Exception {
        McpAccessView view = new ObjectMapper().readValue(body, McpAccessView.class);
        return view.tools().stream().filter(t -> t.toolName().equals(toolName)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("upstream mode constraints and validation are enforced")
    void constraints() throws Exception {
        // Server list on an open server is rejected.
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("mode", "ALLOW", "consumerIds", List.of(consumerA.toString()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVER_LIST_UNSUPPORTED"));

        // Tool override while the server is restricted is rejected.
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/mode", Map.of("mode", "ALLOW"))
                .andExpect(status().isOk());
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("toolId", toolId.toString(), "mode", "ALLOW", "consumerIds", List.of(consumerA.toString())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOOL_ACL_UNSUPPORTED"));

        // Empty consumer list is rejected by bean validation (before any scope rule).
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("mode", "ALLOW", "consumerIds", List.of())).andExpect(status().isBadRequest());

        // Unknown consumer is a 400 while the server list is legitimate.
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("mode", "ALLOW", "consumerIds", List.of(UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSUMER_NOT_FOUND"));

        // Unknown service is a 404.
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + UUID.randomUUID() + "/access").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MCP_SERVICE_NOT_FOUND"));

        // Switching back to NONE drops the list and re-enables tool overrides.
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/mode", Map.of("mode", "NONE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serverConsumers.length()").value(0));
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("toolId", toolId.toString(), "mode", "DENY", "consumerIds", List.of(consumerA.toString())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("access changes are audited")
    void auditTrail() throws Exception {
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/mode", Map.of("mode", "ALLOW"))
                .andExpect(status().isOk());
        putJson("/api/v1/admin/mcp-services/" + serviceId + "/access/grants",
                Map.of("mode", "ALLOW", "consumerIds", List.of(consumerA.toString()))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/mcp-services/" + serviceId + "/access/grants").cookie(sessionCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());

        List<String> actions = jdbc.query(
                "SELECT action FROM admin_audit_events" + " WHERE action LIKE 'MCP_ACCESS_%' ORDER BY created_at",
                new MapSqlParameterSource(), (rs, i) -> rs.getString(1));
        assertThat(actions).containsExactly("MCP_ACCESS_MODE", "MCP_ACCESS_GRANTS", "MCP_ACCESS_RESET");
    }

    @Test
    @DisplayName("unauthenticated callers are rejected")
    void permissions() throws Exception {
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/access"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID createConsumer(String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/api-consumers").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> body = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        Map<?, ?> consumer = (Map<?, ?>) body.get("consumer");
        return UUID.fromString((String) consumer.get("id"));
    }

    private MvcResult postJson(String path, Object payload) throws Exception {
        return mockMvc
                .perform(post(path).contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(payload)))
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String path, Object payload) throws Exception {
        return mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).content(objectMapper.writeValueAsString(payload)));
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
