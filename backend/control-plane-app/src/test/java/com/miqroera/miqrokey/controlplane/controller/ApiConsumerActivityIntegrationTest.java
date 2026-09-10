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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-consumer MCP call overview (#338, I5): mcp_access_log window aggregates
 * (totals by outcome class, top tools/services, last call), tenant isolation,
 * window validation and the empty-window zero view.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Consumer activity overview integration tests (PostgreSQL)")
class ApiConsumerActivityIntegrationTest {

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
    private final String adminUsername = "act_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("mcp_access_log", "api_consumers", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
        jdbc.update("INSERT INTO tenants (id, code, name) VALUES (:id, 'act-it-b', 'Activity IT B')",
                new MapSqlParameterSource("id", OTHER_TENANT_ID));

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
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
        for (String table : List.of("mcp_access_log", "api_consumers", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
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

    private String createConsumer(String name) throws Exception {
        MvcResult created = mockMvc.perform(
                post("/api/v1/admin/api-consumers").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("consumer").get("id").asText();
    }

    private void logRow(UUID tenantId, String consumerId, String consumerName, String serviceName, String toolName,
            String status, Instant at) {
        jdbc.update("""
                INSERT INTO mcp_access_log (id, tenant_id, service_id, service_name, consumer_id, consumer_name,
                    rpc_method, tool_name, status, http_status, gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :serviceId, :serviceName, :consumerId, :consumerName, 'tools/call',
                    :toolName, :status, 200, :requestId, :occurredAt)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                .addValue("serviceId", UUID.randomUUID()).addValue("serviceName", serviceName)
                .addValue("consumerId", UUID.fromString(consumerId)).addValue("consumerName", consumerName)
                .addValue("toolName", toolName).addValue("status", status)
                .addValue("requestId", "gw-" + UUID.randomUUID()).addValue("occurredAt", java.sql.Timestamp.from(at)));
    }

    @Test
    @DisplayName("window aggregates: totals by class, top tools/services, last call, isolation")
    void overview() throws Exception {
        String consumerA = createConsumer("overview-a");
        String consumerB = createConsumer("overview-b");
        Instant now = Instant.now();
        // A: 3 forwarded (t1×2, t2×1), 1 denied, 1 upstream failure; one stale row
        // outside 24h.
        logRow(TENANT_ID, consumerA, "overview-a", "svc-1", "t1", "FORWARDED", now.minusSeconds(600));
        logRow(TENANT_ID, consumerA, "overview-a", "svc-1", "t1", "FORWARDED", now.minusSeconds(500));
        logRow(TENANT_ID, consumerA, "overview-a", "svc-2", "t2", "FORWARDED", now.minusSeconds(400));
        logRow(TENANT_ID, consumerA, "overview-a", "svc-2", "t2", "TOOL_DENIED", now.minusSeconds(300));
        logRow(TENANT_ID, consumerA, "overview-a", "svc-1", "t1", "UPSTREAM_FAILURE", now.minusSeconds(200));
        logRow(TENANT_ID, consumerA, "overview-a", "svc-1", "t1", "FORWARDED", now.minus(30, ChronoUnit.HOURS));
        // B: unrelated consumer and a foreign-tenant row — must not leak into A.
        logRow(TENANT_ID, consumerB, "overview-b", "svc-9", "t9", "FORWARDED", now.minusSeconds(100));
        logRow(OTHER_TENANT_ID, consumerA, "overview-a", "svc-x", "tx", "FORWARDED", now.minusSeconds(100));

        mockMvc.perform(get("/api/v1/admin/api-consumers/" + consumerA + "/activity").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.windowHours").value(24))
                .andExpect(jsonPath("$.totalCalls").value(5)).andExpect(jsonPath("$.forwarded").value(3))
                .andExpect(jsonPath("$.denied").value(1)).andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.lastCallAt").isNotEmpty()).andExpect(jsonPath("$.topTools[0].name").value("t1"))
                .andExpect(jsonPath("$.topTools[0].calls").value(3))
                .andExpect(jsonPath("$.topTools[1].name").value("t2"))
                .andExpect(jsonPath("$.topServices[0].name").value("svc-1"));

        // 7d window picks the stale row up as well.
        mockMvc.perform(get("/api/v1/admin/api-consumers/" + consumerA + "/activity").param("hours", "168")
                .cookie(sessionCookie, csrfCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(6));

        // Empty window view: zeroes, no 404.
        mockMvc.perform(get("/api/v1/admin/api-consumers/" + consumerB + "/activity").param("hours", "1")
                .cookie(sessionCookie, csrfCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(1));
    }

    @Test
    @DisplayName("validation: hours bounds and unknown consumer")
    void validation() throws Exception {
        String consumerId = createConsumer("bounds-c");
        mockMvc.perform(get("/api/v1/admin/api-consumers/" + consumerId + "/activity").param("hours", "0")
                .cookie(sessionCookie, csrfCookie)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/api/v1/admin/api-consumers/" + consumerId + "/activity").param("hours", "169")
                .cookie(sessionCookie, csrfCookie)).andExpect(status().isBadRequest());
        mockMvc.perform(
                get("/api/v1/admin/api-consumers/" + UUID.randomUUID() + "/activity").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONSUMER_NOT_FOUND"));
        // Foreign-tenant consumer id resolves as 404 under this tenant's lookup.
        UUID foreignConsumer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO api_consumers (id, tenant_id, name, key_digest, key_prefix, status, version,
                    created_at, updated_at)
                VALUES (:id, :tenantId, 'foreign-consumer', decode(repeat('00', 32), 'hex'), 'deadbeef', 'ACTIVE', 0,
                    now(), now())
                """, new MapSqlParameterSource("id", foreignConsumer).addValue("tenantId", OTHER_TENANT_ID));
        mockMvc.perform(
                get("/api/v1/admin/api-consumers/" + foreignConsumer + "/activity").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONSUMER_NOT_FOUND"));
    }
}
