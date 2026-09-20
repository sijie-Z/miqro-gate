package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP onboarding closure (#685): gateway access URLs and the read-only
 * on-demand probe ("调用验证"). The probe runs against a real loopback HTTP server;
 * the service rows are inserted directly because the registration validation
 * intentionally rejects literal loopback endpoints (#477).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP onboarding API integration tests (PostgreSQL)")
class McpOnboardingApiIntegrationTest {

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

    private HttpServer server;
    private int port;
    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID adminId;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();

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
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'root'", new MapSqlParameterSource(),
                UUID.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        clean();
    }

    /** Mirrors the sibling MCP IT: child-first delete of the canonical FK set. */
    private void clean() {
        for (String table : new String[]{"mcp_services", "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("access returns the gateway URLs mirroring the data-plane route (#685)")
    void accessReturnsGatewayUrls() throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper
                        .writeValueAsString(Map.of("name", "demo-mcp", "endpoint", "https://mcp.example.com/mcp"))))
                .andExpect(status().isOk());
        String serviceId = jdbc.queryForObject("SELECT id::text FROM mcp_services WHERE name = 'demo-mcp'",
                new MapSqlParameterSource(), String.class);

        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/connection").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("demo-mcp"))
                .andExpect(jsonPath("$.mcpUrl").value(org.hamcrest.Matchers.endsWith("/mcpservers/demo-mcp/mcp")))
                .andExpect(jsonPath("$.sseUrl").value(org.hamcrest.Matchers.endsWith("/mcpservers/demo-mcp/sse")))
                .andExpect(jsonPath("$.authHint").value(org.hamcrest.Matchers.containsString("mcp:call")));
    }

    @Test
    @DisplayName("verify probes the upstream live and never touches stored health telemetry (#685)")
    void verifyProbesLiveAndIsReadOnly() throws Exception {
        server.createContext("/mcp", exchange -> {
            byte[] payload = "{\"jsonrpc\":\"2.0\",\"id\":\"miqrokey-health\",\"result\":{\"protocolVersion\":\"2025-06-18\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        UUID okId = insertService("probe-ok", "http://127.0.0.1:" + port + "/mcp", "JSONRPC_INITIALIZE");
        UUID badId = insertService("probe-bad", "http://127.0.0.1:1", "HEALTH_PATH");

        mockMvc.perform(post("/api/v1/admin/mcp-services/" + okId + "/verify").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.checkMode").value("JSONRPC_INITIALIZE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("通过")))
                .andExpect(jsonPath("$.latencyMs").isNumber());

        mockMvc.perform(post("/api/v1/admin/mcp-services/" + badId + "/verify").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("连接失败")));

        // Read-only: the scheduled checker owns telemetry — verify leaves it alone.
        assertThat(jdbc.queryForList("SELECT health_checked_at FROM mcp_services", new MapSqlParameterSource()))
                .allSatisfy(row -> assertThat(row.get("health_checked_at")).isNull());
    }

    @Test
    @DisplayName("access and verify stay tenant-scoped and unknown services are 404 (#685)")
    void unknownServiceIs404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + UUID.randomUUID() + "/connection").cookie(sessionCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + UUID.randomUUID() + "/verify")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound());
    }

    private UUID insertService(String name, String endpoint, String checkMode) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mcp_services (id, tenant_id, name, endpoint, transport, status, check_mode, created_by)
                VALUES (:id, :tenantId, :name, :endpoint, 'STREAMABLE_HTTP', 'ONLINE', :checkMode, :createdBy)
                """, new MapSqlParameterSource("id", id)
                .addValue("tenantId", UUID.fromString("00000000-0000-0000-0000-000000000001")).addValue("name", name)
                .addValue("endpoint", endpoint).addValue("checkMode", checkMode).addValue("createdBy", adminId));
        return id;
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
