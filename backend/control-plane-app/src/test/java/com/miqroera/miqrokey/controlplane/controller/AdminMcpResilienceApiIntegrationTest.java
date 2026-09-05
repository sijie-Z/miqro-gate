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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F12/F13 resilience configuration API (api-contract §5.25, V30): default
 * disabled view, full replace, cross-field validation (retry conditions and
 * cap, breaker trigger presence, slow threshold vs the service check timeout,
 * probe relation) and the audit trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("MCP resilience API integration tests (PostgreSQL)")
class AdminMcpResilienceApiIntegrationTest {

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
    private UUID adminUserId;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("mcp_resilience_policy", "mcp_service_access", "mcp_access_grants", "mcp_tools",
                "mcp_route_rule", "mcp_services", "user_sessions", "admin_audit_events", "users")) {
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
        adminUserId = UUID.fromString((String) bootBody.get("userId"));
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
        serviceId = insertService(3);
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("mcp_resilience_policy", "mcp_service_access", "mcp_access_grants", "mcp_tools",
                "mcp_route_rule", "mcp_services", "user_sessions", "admin_audit_events", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
    }

    private UUID insertService(int checkTimeoutSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mcp_services (id, tenant_id, name, endpoint, transport, status, check_interval_seconds,
                    check_timeout_seconds, fail_threshold, recover_threshold, check_path, health_status, version,
                    created_by)
                VALUES (:id, :tenantId, :name, 'https://mcp.example.test/mcp', 'STREAMABLE_HTTP', 'ONLINE', 30,
                    :checkTimeout, 3, 1, '/health', 'UNKNOWN', 0, :createdBy)
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("tenantId", TENANT_ID)
                        .addValue("name", "resilience-" + id.toString().substring(0, 8))
                        .addValue("checkTimeout", checkTimeoutSeconds).addValue("createdBy", adminUserId));
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

    private Map<String, Object> payload(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("GET returns the fully disabled default when no row exists")
    void defaultDisabledView() throws Exception {
        mockMvc.perform(get("/api/v1/admin/mcp-services/{id}/resilience", serviceId).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retryEnabled").value(false))
                .andExpect(jsonPath("$.breakerEnabled").value(false));
    }

    @Test
    @DisplayName("PUT replaces the policy and echoes stored defaults")
    void configureRoundTrip() throws Exception {
        mockMvc.perform(
                put("/api/v1/admin/mcp-services/{id}/resilience", serviceId).cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("retryEnabled", true, "retryMax", 2,
                                "retryConditions", List.of("SERVER_5XX"), "idempotencyConfirmed", true,
                                "breakerEnabled", true, "breakerMinRequests", 5))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retryEnabled").value(true))
                .andExpect(jsonPath("$.retryMax").value(2)).andExpect(jsonPath("$.retryConditions", hasSize(1)))
                .andExpect(jsonPath("$.breakerEnabled").value(true))
                .andExpect(jsonPath("$.breakerMinRequests").value(5))
                .andExpect(jsonPath("$.breakerErrorEnabled").value(true))
                .andExpect(jsonPath("$.breakerErrorStatusCodes", hasSize(4)))
                .andExpect(jsonPath("$.breakerErrorStatusCodes", org.hamcrest.Matchers.hasItems(500, 502, 503, 504)))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(get("/api/v1/admin/mcp-services/{id}/resilience", serviceId).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retryEnabled").value(true));

        // Audit trail carries the change.
        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_events WHERE action = 'MCP_RESILIENCE_UPDATE'",
                new MapSqlParameterSource(), Integer.class);
        org.assertj.core.api.Assertions.assertThat(auditRows).isEqualTo(1);
    }

    @Test
    @DisplayName("validation matrix")
    void validation() throws Exception {
        String path = "/api/v1/admin/mcp-services/" + serviceId + "/resilience";
        // retry enabled without conditions
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload("retryEnabled", true, "retryMax", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESILIENCE_INVALID"));
        // retryMax out of range
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        payload("retryEnabled", true, "retryMax", 9, "retryConditions", List.of("SERVER_5XX")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESILIENCE_INVALID"));
        // unknown retry condition
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        payload("retryEnabled", true, "retryMax", 1, "retryConditions", List.of("BOGUS")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESILIENCE_INVALID"));
        // breaker with both triggers disabled
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        payload("breakerEnabled", true, "breakerErrorEnabled", false, "breakerSlowEnabled", false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESILIENCE_INVALID"));
        // slow threshold at the service check timeout (3s -> 3000ms) is rejected
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        payload("breakerEnabled", true, "breakerSlowEnabled", true, "breakerSlowCallMs", 3000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESILIENCE_SLOW_EXCEEDS_TIMEOUT"));
        // ... and 2999ms passes
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        payload("breakerEnabled", true, "breakerSlowEnabled", true, "breakerSlowCallMs", 2999))))
                .andExpect(status().isOk());
        // probe success beyond probe count
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        payload("breakerEnabled", true, "breakerProbeCount", 3, "breakerProbeSuccess", 4))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESILIENCE_INVALID"));
        // status codes outside 400..599
        mockMvc.perform(put(path).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper
                        .writeValueAsString(payload("breakerEnabled", true, "breakerErrorStatusCodes", List.of(600)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESILIENCE_INVALID"));
    }

    @Test
    @DisplayName("unknown service 404 and anonymous 401")
    void boundaries() throws Exception {
        mockMvc.perform(get("/api/v1/admin/mcp-services/{id}/resilience", UUID.randomUUID()).cookie(sessionCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MCP_SERVICE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/mcp-services/{id}/resilience", serviceId))
                .andExpect(status().isUnauthorized());
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
