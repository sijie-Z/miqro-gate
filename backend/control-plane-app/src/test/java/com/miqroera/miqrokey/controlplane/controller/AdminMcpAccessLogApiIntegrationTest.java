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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP access log audit query (F15, api-contract §5.23): newest-first listing of
 * gateway-written {@code mcp_access_log} rows with optional
 * service/consumer/window filters, limit cap, validation and the
 * SYSTEM_ADMIN-only boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP access log API integration tests (PostgreSQL)")
class AdminMcpAccessLogApiIntegrationTest {

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

    @BeforeEach
    void setUp() throws Exception {
        // Bootstrap is single-flight per DB state: clear the user chain first so
        // every test boots a fresh admin (shared-container convention).
        for (String table : List.of("mcp_access_log", "user_sessions", "admin_audit_events", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
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
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("mcp_access_log", "user_sessions", "admin_audit_events", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final UUID CONSUMER_ID = UUID.randomUUID();

    private void insertRow(String id, String serviceName, String consumerName, String method, String tool,
            String status, Integer httpStatus, String occurredAtOffset) {
        jdbc.update("""
                INSERT INTO mcp_access_log (id, tenant_id, service_id, service_name, consumer_id, consumer_name,
                    rpc_method, tool_name, status, http_status, gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :serviceId, :serviceName, :consumerId, :consumerName, :method, :tool,
                    :status, :httpStatus, :gatewayRequestId, now() - :offset::interval)
                """, new MapSqlParameterSource().addValue("id", UUID.fromString(id)).addValue("tenantId", TENANT_ID)
                .addValue("serviceId", SERVICE_ID).addValue("serviceName", serviceName)
                .addValue("consumerId", CONSUMER_ID).addValue("consumerName", consumerName).addValue("method", method)
                .addValue("tool", tool).addValue("status", status).addValue("httpStatus", httpStatus)
                .addValue("gatewayRequestId", id).addValue("offset", occurredAtOffset));
    }

    private void insertThreeRows() {
        // Newest first by design: (…, "1 hour ago") is the newest of the three.
        insertRow("00000000-0000-0000-0000-000000000001", "weather-mcp", "drill-allowed", "tools/call", "forecast",
                "FORWARDED", 200, "1 hour");
        insertRow("00000000-0000-0000-0000-000000000002", "weather-mcp", "drill-outside", "tools/list", null,
                "SERVICE_DENIED", 403, "3 hours");
        insertRow("00000000-0000-0000-0000-000000000003", "files-mcp", "drill-allowed", "initialize", null, "FORWARDED",
                200, "5 hours");
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
    @DisplayName("lists rows newest first within the default window")
    void listsNewestFirst() throws Exception {
        insertThreeRows();
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].gatewayRequestId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$[0].status").value("FORWARDED")).andExpect(jsonPath("$[0].httpStatus").value(200))
                .andExpect(jsonPath("$[0].serviceName").value("weather-mcp"))
                .andExpect(jsonPath("$[0].toolName").value("forecast"))
                .andExpect(jsonPath("$[1].status").value("SERVICE_DENIED"))
                .andExpect(jsonPath("$[1].httpStatus").value(403))
                .andExpect(jsonPath("$[2].serviceName").value("files-mcp"))
                .andExpect(jsonPath("$[2].toolName").value(nullValue()));
    }

    @Test
    @DisplayName("filters by service, consumer and window")
    void filters() throws Exception {
        insertThreeRows();
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("service", "weather-mcp").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("consumer", "drill-allowed").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("service", "weather-mcp")
                .param("consumer", "drill-allowed").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // Window ending two hours ago (opening ten days ago — inside the 31-day
        // cap) keeps the two older rows and excludes the newest (occurred_at >=
        // from AND < to).
        String tenDaysAgo = jdbc.queryForObject(
                "SELECT to_char(now() AT TIME ZONE 'UTC' - interval '10 days', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')",
                new MapSqlParameterSource(), String.class);
        String twoHoursAgo = jdbc.queryForObject(
                "SELECT to_char(now() AT TIME ZONE 'UTC' - interval '2 hours', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')",
                new MapSqlParameterSource(), String.class);
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("from", tenDaysAgo).param("to", twoHoursAgo)
                .cookie(sessionCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("service", "no-such-service").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("caps the result at limit")
    void limitCaps() throws Exception {
        insertThreeRows();
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("limit", "2").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].gatewayRequestId").value("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    @DisplayName("validates params and the SYSTEM_ADMIN boundary")
    void validation() throws Exception {
        insertThreeRows();
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("from", "2026-09-04T00:00:00Z")
                .param("to", "2026-09-03T00:00:00Z").cookie(sessionCookie)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("from", "2020-01-01T00:00:00Z")
                .param("to", "2026-09-05T00:00:00Z").cookie(sessionCookie)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_TOO_WIDE"));
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("limit", "0").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SIZE_INVALID"));
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").param("from", "not-an-instant").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a non-admin caller")
    void rejectsRegularUser() throws Exception {
        insertThreeRows();
        MvcResult reg = mockMvc
                .perform(
                        post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("username",
                                        "plain_" + UUID.randomUUID().toString().substring(0, 8), "nickname", "Plain",
                                        "password", "NewSecurePass1!", "confirmPassword", "NewSecurePass1!"))))
                .andExpect(status().isCreated()).andReturn();
        Cookie regSession = cookie(reg, "MIQROKEY_SESSION");
        assertThat(regSession).isNotNull();
        mockMvc.perform(get("/api/v1/admin/mcp-access-logs").cookie(regSession)).andExpect(status().isForbidden());
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
