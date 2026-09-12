package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.service.McpHealthChecker;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP service management (P3.4): registration with defaults, endpoint
 * validation, manual online/offline switching and health config updates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Admin MCP service API integration tests (PostgreSQL)")
class AdminMcpServiceApiIntegrationTest {

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
    @Autowired
    McpHealthChecker healthChecker;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;

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
    }

    @AfterEach
    void tearDown() {
        clean();
    }

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
    @DisplayName("mcp service endpoints require authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/mcp-services")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("mcp service lifecycle: register with defaults, list, offline/online")
    void mcpLifecycle() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"erp-mcp\",\"description\":\"ERP MCP server\","
                                + "\"endpoint\":\"https://erp.internal.example\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("erp-mcp"))
                .andExpect(jsonPath("$.transport").value("STREAMABLE_HTTP"))
                .andExpect(jsonPath("$.status").value("ONLINE")).andExpect(jsonPath("$.healthStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.checkIntervalSeconds").value(30))
                .andExpect(jsonPath("$.checkPath").value("/health")).andReturn();
        String serviceId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        mockMvc.perform(get("/api/v1/admin/mcp-services").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Manual offline, then online; duplicate switch conflicts.
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/status?status=OFFLINE")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/status?status=OFFLINE")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MCP_STATUS_UNCHANGED"));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/status?status=ONLINE")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONLINE"));

        // Health config update, including the #387 probe shape.
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/health-config")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkIntervalSeconds\":60,\"checkTimeoutSeconds\":10,\"failThreshold\":5,"
                        + "\"recoverThreshold\":2,\"checkPath\":\"/ready\"," + "\"checkMode\":\"JSONRPC_INITIALIZE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkIntervalSeconds").value(60))
                .andExpect(jsonPath("$.checkPath").value("/ready"))
                .andExpect(jsonPath("$.checkMode").value("JSONRPC_INITIALIZE"));

        // Unknown probe shape is rejected; the stored value is untouched.
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + serviceId + "/health-config")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"checkMode\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MCP_CHECK_MODE_INVALID"));
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId).cookie(sessionCookie))
                .andExpect(jsonPath("$.checkMode").value("JSONRPC_INITIALIZE"));
    }

    @Test
    @DisplayName("mcp service registration validates the endpoint and the name")
    void mcpValidation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\",\"endpoint\":\"http://insecure.example\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MCP_ENDPOINT_INVALID"));
        mockMvc.perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\",\"endpoint\":\"https://user:pass@example.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MCP_ENDPOINT_INVALID"));

        String body = "{\"name\":\"dup\",\"endpoint\":\"https://dup.example\"}";
        mockMvc.perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MCP_SERVICE_NAME_TAKEN"));

        mockMvc.perform(post("/api/v1/admin/mcp-services/" + UUID.randomUUID() + "/status?status=ONLINE")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MCP_SERVICE_NOT_FOUND"));
    }

    private static Cookie cookie(MvcResult result, String name) {
        return java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("upstream budget (I20): default, create override, PUT round-trip, range validation")
    void upstreamTimeoutBudget() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"budget-mcp\",\"endpoint\":\"https://budget.example\","
                                + "\"upstreamTimeoutMs\":8000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upstreamTimeoutMs").value(8000)).andReturn();
        String id = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();

        // Doc 135906 default when omitted.
        mockMvc.perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"default-mcp\",\"endpoint\":\"https://default.example\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upstreamTimeoutMs").value(60000));

        mockMvc.perform(put("/api/v1/admin/mcp-services/" + id + "/upstream-timeout").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"upstreamTimeoutMs\":20000}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreamTimeoutMs").value(20000));
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + id).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreamTimeoutMs").value(20000));

        mockMvc.perform(put("/api/v1/admin/mcp-services/" + id + "/upstream-timeout").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"upstreamTimeoutMs\":500}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MCP_TIMEOUT_INVALID"));
        mockMvc.perform(put("/api/v1/admin/mcp-services/" + id + "/upstream-timeout").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"upstreamTimeoutMs\":700000}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MCP_TIMEOUT_INVALID"));
        mockMvc.perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad-budget\",\"endpoint\":\"https://bad.example\","
                        + "\"upstreamTimeoutMs\":100}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MCP_TIMEOUT_INVALID"));
    }

    @Test
    @DisplayName("a health-probe cycle writes telemetry only — version stays put and a status switch still succeeds (#415)")
    void probeCycleDoesNotRaceAdminEdits() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"probe-mcp\",\"endpoint\":\"https://probe.internal.example\"}"))
                .andExpect(status().isOk()).andReturn();
        String id = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();

        healthChecker.checkAll();

        mockMvc.perform(get("/api/v1/admin/mcp-services/" + id).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.healthCheckedAt").isNotEmpty())
                .andExpect(jsonPath("$.consecutiveFailures").value(1));
        mockMvc.perform(post("/api/v1/admin/mcp-services/" + id + "/status?status=OFFLINE")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));
    }

    @Test
    @DisplayName("an admin edit losing the optimistic lock is 409 CONCURRENT_MODIFICATION, never a 500 (#415)")
    void concurrentEditConflictMapsTo409() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"race-mcp\",\"endpoint\":\"https://race.internal.example\"}"))
                .andExpect(status().isOk()).andReturn();
        String id = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();

        // T2 (this connection) bumps the version WITHOUT committing — the row
        // lock is held. T1's status switch reads version 0, then blocks on that
        // lock in its version-guarded UPDATE; after the commit the UPDATE
        // re-evaluates against version 1 and loses — a retry-shaped conflict,
        // never a 500.
        var dataSource = jdbc.getJdbcTemplate().getDataSource();
        ExecutorService io = Executors.newSingleThreadExecutor();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn
                    .prepareStatement("UPDATE mcp_services SET version = version + 1 WHERE id = ?")) {
                ps.setObject(1, UUID.fromString(id));
                ps.executeUpdate();
            }
            Future<Integer> status = io.submit(() -> mockMvc
                    .perform(post("/api/v1/admin/mcp-services/" + id + "/status?status=OFFLINE")
                            .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                    .andReturn().getResponse().getStatus());
            Thread.sleep(500);
            org.assertj.core.api.Assertions.assertThat(status.isDone()).as("must wait on the row lock").isFalse();
            conn.commit();
            org.assertj.core.api.Assertions.assertThat(status.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            io.shutdownNow();
        }
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + id).cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONLINE")).andExpect(jsonPath("$.version").value(1));
    }
}
