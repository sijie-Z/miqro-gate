package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Passive health view (#397): per-service real-traffic window aggregates from
 * mcp_access_log (classification mirrors the consumer activity overview #338),
 * window switching, the zero/null empty view, tenant isolation and validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP service traffic overview integration tests (PostgreSQL)")
class McpServiceTrafficIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

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

    @BeforeEach
    void setUp() throws Exception {
        clean();
        jdbc.update("INSERT INTO tenants (id, code, name) VALUES (:id, 'traffic-it-b', 'Traffic IT B')",
                new MapSqlParameterSource("id", OTHER_TENANT_ID));
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper
                                .writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(), "root", "Admin"))))
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
        // Child tables (tools/routes/access/resilience) cascade from mcp_services.
        for (String table : new String[]{"mcp_access_log", "mcp_services", "user_sessions", "users",
                "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set (same tolerance as
                // AdminMcpServiceApiIntegrationTest).
            }
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
    }

    private static Cookie cookie(MvcResult result, String name) {
        if (result.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie c : result.getResponse().getCookies()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private String createService(String name) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/mcp-services").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"endpoint\":\"https://" + name + ".internal.example\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private void logRow(UUID tenantId, String serviceId, String serviceName, String toolName, String status,
            Instant at) {
        jdbc.update("""
                INSERT INTO mcp_access_log (id, tenant_id, service_id, service_name, consumer_id, consumer_name,
                    rpc_method, tool_name, status, http_status, gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :serviceId, :serviceName, :consumerId, :consumerName, 'tools/call',
                    :toolName, :status, 200, :requestId, :occurredAt)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                .addValue("serviceId", UUID.fromString(serviceId)).addValue("serviceName", serviceName)
                .addValue("consumerId", UUID.randomUUID()).addValue("consumerName", "traffic-consumer")
                .addValue("toolName", toolName).addValue("status", status)
                .addValue("requestId", "gw-" + UUID.randomUUID()).addValue("occurredAt", java.sql.Timestamp.from(at)));
    }

    @Test
    @DisplayName("window aggregates: classes, failure rate, last failure, top failing tools, isolation")
    void overview() throws Exception {
        String serviceId = createService("traffic-svc");
        String otherServiceId = createService("traffic-other");
        Instant now = Instant.now();
        // 3 forwarded (t1 x2, t2 x1), 1 denied, 1 upstream failure (t1), 1 circuit
        // open (t2); one stale row outside 24h; other-service and foreign-tenant
        // rows must not leak into this service's window.
        logRow(TENANT_ID, serviceId, "traffic-svc", "t1", "FORWARDED", now.minusSeconds(600));
        logRow(TENANT_ID, serviceId, "traffic-svc", "t1", "FORWARDED", now.minusSeconds(500));
        logRow(TENANT_ID, serviceId, "traffic-svc", "t2", "FORWARDED", now.minusSeconds(400));
        logRow(TENANT_ID, serviceId, "traffic-svc", "t2", "TOOL_DENIED", now.minusSeconds(300));
        logRow(TENANT_ID, serviceId, "traffic-svc", "t1", "UPSTREAM_FAILURE", now.minusSeconds(200));
        logRow(TENANT_ID, serviceId, "traffic-svc", "t2", "CIRCUIT_OPEN", now.minusSeconds(100));
        logRow(TENANT_ID, serviceId, "traffic-svc", "t1", "FORWARDED", now.minus(30, ChronoUnit.HOURS));
        logRow(TENANT_ID, otherServiceId, "traffic-other", "t9", "UPSTREAM_FAILURE", now.minusSeconds(50));
        logRow(OTHER_TENANT_ID, serviceId, "traffic-svc", "tx", "UPSTREAM_FAILURE", now.minusSeconds(50));

        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/traffic").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serviceName").value("traffic-svc"))
                .andExpect(jsonPath("$.windowHours").value(24)).andExpect(jsonPath("$.totalCalls").value(6))
                .andExpect(jsonPath("$.forwarded").value(3)).andExpect(jsonPath("$.denied").value(1))
                .andExpect(jsonPath("$.failed").value(2)).andExpect(jsonPath("$.failureRate").value(0.4))
                .andExpect(jsonPath("$.lastCallAt").isNotEmpty()).andExpect(jsonPath("$.lastFailureAt").isNotEmpty())
                .andExpect(jsonPath("$.topFailingTools[0].name").value("t1"))
                .andExpect(jsonPath("$.topFailingTools[0].failures").value(1))
                .andExpect(jsonPath("$.topFailingTools[1].name").value("t2"));

        // 7d window also picks up the stale row.
        mockMvc.perform(
                get("/api/v1/admin/mcp-services/" + serviceId + "/traffic").param("hours", "168").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCalls").value(7));
    }

    @Test
    @DisplayName("denied-only window has a null failure rate; empty window returns the zero view")
    void zeroAndNullViews() throws Exception {
        String serviceId = createService("traffic-denied");
        Instant now = Instant.now();
        logRow(TENANT_ID, serviceId, "traffic-denied", "t1", "TOOL_DENIED", now.minus(5, ChronoUnit.HOURS));
        logRow(TENANT_ID, serviceId, "traffic-denied", "t1", "SERVICE_DENIED", now.minus(4, ChronoUnit.HOURS));

        // Denials are not health failures: no rate, no last-failure, no failing tools.
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + serviceId + "/traffic").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCalls").value(2))
                .andExpect(jsonPath("$.forwarded").value(0)).andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.failureRate").value(nullValue()))
                .andExpect(jsonPath("$.lastFailureAt").value(nullValue()))
                .andExpect(jsonPath("$.topFailingTools").isEmpty());

        // Empty (1h) window: zeroes, no 404.
        mockMvc.perform(
                get("/api/v1/admin/mcp-services/" + serviceId + "/traffic").param("hours", "1").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCalls").value(0))
                .andExpect(jsonPath("$.failureRate").value(nullValue()))
                .andExpect(jsonPath("$.lastCallAt").value(nullValue()));
    }

    @Test
    @DisplayName("validation: hours bounds, unknown service, foreign-tenant service")
    void validation() throws Exception {
        String serviceId = createService("traffic-bounds");
        mockMvc.perform(
                get("/api/v1/admin/mcp-services/" + serviceId + "/traffic").param("hours", "0").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(
                get("/api/v1/admin/mcp-services/" + serviceId + "/traffic").param("hours", "169").cookie(sessionCookie))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + UUID.randomUUID() + "/traffic").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MCP_SERVICE_NOT_FOUND"));

        // Foreign-tenant service id resolves as 404 under this tenant's lookup
        // (tenant 42 exists from setUp).
        UUID foreignService = UUID.randomUUID();
        UUID createdBy = jdbc.queryForObject("SELECT id FROM users WHERE username = 'root'",
                new MapSqlParameterSource(), UUID.class);
        jdbc.update("""
                INSERT INTO mcp_services (id, tenant_id, name, endpoint, created_by)
                VALUES (:id, :tenantId, 'foreign-svc', 'https://foreign.internal.example', :createdBy)
                """, new MapSqlParameterSource("id", foreignService).addValue("tenantId", OTHER_TENANT_ID)
                .addValue("createdBy", createdBy));
        mockMvc.perform(get("/api/v1/admin/mcp-services/" + foreignService + "/traffic").cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MCP_SERVICE_NOT_FOUND"));
    }
}
