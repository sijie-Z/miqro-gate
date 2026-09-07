package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.AuditService;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Open admin read surface (ADR-0015, batch 1 + 1b) end to end: Bearer machine
 * keys exercise every read endpoint — usage summary/records, audit tail, key
 * listing, quota rules, export task metadata and the MCP access log — with the
 * SYSTEM_ADMIN-only session rule, immediate revocation/expiry and tenant
 * isolation between machine keys of different tenants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Open admin API read surface integration tests (PostgreSQL)")
class OpenAdminReadApiIntegrationTest {

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
    @Autowired
    AuditService auditService;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final String KEY_PREFIX = "mqk_admin_it";

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID adminUserId;
    private final String adminUsername = "adm_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("mcp_access_log", "export_tasks", "quota_rules", "admin_api_keys",
                "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
        jdbc.update("""
                INSERT INTO tenants (id, code, name) VALUES (:id, 'openapi-it-b', 'OpenAdmin IT tenant B')
                """, new MapSqlParameterSource("id", OTHER_TENANT_ID));

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
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
        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername), UUID.class);
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("mcp_access_log", "export_tasks", "quota_rules", "admin_api_keys",
                "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
    }

    // ------------------------------------------------------------------ seeds

    private UUID insertKey(UUID tenantId, String name, String token, Instant expiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO admin_api_keys (id, tenant_id, name, key_digest, key_prefix, created_by, expires_at,
                    revoked_at, created_at)
                VALUES (:id, :tenantId, :name, :digest, :prefix, :createdBy, :expiresAt, :revokedAt, now())
                """, new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("name", name)
                .addValue("digest", digest(token)).addValue("prefix", KEY_PREFIX).addValue("createdBy", adminUserId)
                .addValue("expiresAt", expiresAt != null ? java.sql.Timestamp.from(expiresAt) : null)
                .addValue("revokedAt", revokedAt != null ? java.sql.Timestamp.from(revokedAt) : null));
        return id;
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertMcpRow(UUID tenantId, String requestId, String serviceName) {
        jdbc.update("""
                INSERT INTO mcp_access_log (id, tenant_id, service_id, service_name, consumer_id, consumer_name,
                    rpc_method, tool_name, status, http_status, gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :serviceId, :serviceName, :consumerId, :consumerName, 'tools/call', 'forecast',
                    'FORWARDED', 200, :gatewayRequestId, now() - interval '1 hour')
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                        .addValue("serviceId", UUID.randomUUID()).addValue("serviceName", serviceName)
                        .addValue("consumerId", UUID.randomUUID()).addValue("consumerName", "it-consumer")
                        .addValue("gatewayRequestId", requestId));
    }

    private void insertExport(UUID id, String format, Instant createdAt) {
        jdbc.update("""
                INSERT INTO export_tasks (id, tenant_id, created_by, format, period_from, period_to, status,
                    created_at)
                VALUES (:id, :tenantId, :createdBy, :format, :periodFrom, :periodTo, 'PENDING', :createdAt)
                """,
                new MapSqlParameterSource("id", id).addValue("tenantId", TENANT_ID).addValue("createdBy", adminUserId)
                        .addValue("format", format)
                        .addValue("periodFrom", java.sql.Timestamp.from(createdAt.minusSeconds(3600)))
                        .addValue("periodTo", java.sql.Timestamp.from(createdAt))
                        .addValue("createdAt", java.sql.Timestamp.from(createdAt)));
    }

    private void insertQuotaRule(UUID id) {
        jdbc.update("""
                INSERT INTO quota_rules (id, tenant_id, scope_type, scope_id, metric, period, limit_value,
                    warn_percent, status, created_by)
                VALUES (:id, :tenantId, 'USER', :scopeId, 'TOKENS', 'DAILY', 1000000, 80, 'ACTIVE', :createdBy)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", TENANT_ID)
                .addValue("scopeId", adminUserId).addValue("createdBy", adminUserId));
    }

    private void audit(UUID tenantId, UUID actorId, String action) {
        auditService.record(tenantId, actorId, action, "TEST", null, "{}", null);
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

    private void seedTenantOneSurface() {
        insertKey(TENANT_ID, "ops-a", "mqk_admin_it_token_a", null, null);
        insertKey(TENANT_ID, "ops-b", "mqk_admin_it_token_b", null, null);
        insertQuotaRule(UUID.randomUUID());
        insertExport(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "CSV", Instant.now().minusSeconds(7200));
        insertExport(UUID.fromString("00000000-0000-0000-0000-0000000000a2"), "JSONL",
                Instant.now().minusSeconds(3600));
        insertMcpRow(TENANT_ID, "it-tenant1-row-1", "weather-mcp");
        insertMcpRow(TENANT_ID, "it-tenant1-row-2", "files-mcp");
        insertMcpRow(TENANT_ID, "it-tenant1-row-3", "calendar-mcp");
        audit(TENANT_ID, adminUserId, "MACHINE_TEST_EVENT");
        audit(TENANT_ID, adminUserId, "MACHINE_TEST_EVENT");
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a valid machine key reaches every open read endpoint tenant-scoped")
    void machineKeyReadsEveryOpenEndpoint() throws Exception {
        seedTenantOneSurface();

        mockMvc.perform(get("/api/v1/admin-api/usage/summary").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin-api/usage/records").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(50));

        mockMvc.perform(get("/api/v1/admin-api/audit-events").param("action", "MACHINE_TEST_EVENT")
                .header("Authorization", "Bearer mqk_admin_it_token_a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].chainPosition").isNumber());

        mockMvc.perform(get("/api/v1/admin-api/api-keys").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].prefix").value(KEY_PREFIX)).andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].secret").doesNotExist()).andExpect(jsonPath("$[0].keyDigest").doesNotExist());

        mockMvc.perform(get("/api/v1/admin-api/quota-rules").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].metric").value("TOKENS"));

        mockMvc.perform(get("/api/v1/admin-api/export-tasks").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].format").value("JSONL")).andExpect(jsonPath("$[0].fileBytes").doesNotExist());
        mockMvc.perform(
                get("/api/v1/admin-api/export-tasks/{id}", UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
                        .header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.format").value("CSV"));
        mockMvc.perform(get("/api/v1/admin-api/export-tasks/{id}", UUID.randomUUID()).header("Authorization",
                "Bearer mqk_admin_it_token_a")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/admin-api/mcp-access-logs").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("machine keys never cross tenant lines")
    void tenantIsolation() throws Exception {
        seedTenantOneSurface();
        insertKey(OTHER_TENANT_ID, "other-ops", "mqk_admin_it_other_key", null, null);
        insertMcpRow(OTHER_TENANT_ID, "it-tenant2-row-1", "other-mcp");
        audit(OTHER_TENANT_ID, null, "TENANT_TWO_EVENT");

        mockMvc.perform(get("/api/v1/admin-api/mcp-access-logs").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/v1/admin-api/api-keys").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("ops-a", "ops-b")));
        mockMvc.perform(get("/api/v1/admin-api/audit-events").param("action", "TENANT_TWO_EVENT")
                .header("Authorization", "Bearer mqk_admin_it_token_a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(
                get("/api/v1/admin-api/mcp-access-logs").header("Authorization", "Bearer mqk_admin_it_other_key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].gatewayRequestId").value("it-tenant2-row-1"));
        mockMvc.perform(get("/api/v1/admin-api/api-keys").header("Authorization", "Bearer mqk_admin_it_other_key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("other-ops"));
        mockMvc.perform(get("/api/v1/admin-api/audit-events").param("action", "TENANT_TWO_EVENT")
                .header("Authorization", "Bearer mqk_admin_it_other_key")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("missing, unknown, revoked and expired credentials all get 401")
    void credentialNegatives() throws Exception {
        insertKey(TENANT_ID, "revoked", "mqk_admin_it_revoked", null, Instant.now());
        insertKey(TENANT_ID, "expired", "mqk_admin_it_expired", Instant.now().minusSeconds(60), null);

        mockMvc.perform(get("/api/v1/admin-api/audit-events")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_API_KEY_INVALID"));
        mockMvc.perform(
                get("/api/v1/admin-api/audit-events").header("Authorization", "Bearer mqk_admin_it_no-such-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin-api/audit-events").header("Authorization", "Bearer mqk_admin_it_revoked"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin-api/audit-events").header("Authorization", "Bearer mqk_admin_it_expired"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SYSTEM_ADMIN sessions pass with their tenant; regular sessions are forbidden (batch 1b)")
    void sessionRules() throws Exception {
        audit(TENANT_ID, adminUserId, "MACHINE_TEST_EVENT");

        // SYSTEM_ADMIN session: tenant attribute is seeded from the session.
        mockMvc.perform(
                get("/api/v1/admin-api/audit-events").param("action", "MACHINE_TEST_EVENT").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/admin-api/usage/summary").cookie(sessionCookie)).andExpect(status().isOk());

        // Regular registered user: session alone is not an open-surface credential.
        MvcResult reg = mockMvc
                .perform(
                        post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("username",
                                        "plain_" + UUID.randomUUID().toString().substring(0, 8), "nickname", "Plain",
                                        "password", "NewSecurePass1!", "confirmPassword", "NewSecurePass1!"))))
                .andExpect(status().isCreated()).andReturn();
        Cookie regSession = cookie(reg, "MIQROKEY_SESSION");
        assertThat(regSession).isNotNull();
        mockMvc.perform(get("/api/v1/admin-api/audit-events").cookie(regSession)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_API_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin-api/usage/summary").cookie(regSession)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the session admin audit endpoint still works through the shared read service")
    void humanAuditParity() throws Exception {
        audit(TENANT_ID, adminUserId, "HUMAN_PARITY_EVENT");
        mockMvc.perform(get("/api/v1/admin/audit-events").param("action", "HUMAN_PARITY_EVENT").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("HUMAN_PARITY_EVENT"));
    }

    @Test
    @DisplayName("audit tail keeps causal order and honours the cursor")
    void auditOrderingAndCursor() throws Exception {
        insertKey(TENANT_ID, "ops-a", "mqk_admin_it_token_a", null, null);
        for (int i = 0; i < 3; i++) {
            audit(TENANT_ID, adminUserId, "CURSOR_EVENT");
        }
        MvcResult all = mockMvc
                .perform(get("/api/v1/admin-api/audit-events").param("action", "CURSOR_EVENT").header("Authorization",
                        "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andReturn();
        List<Map<String, Object>> events = objectMapper.readValue(all.getResponse().getContentAsString(),
                new TypeReference<>() {
                });
        long newest = ((Number) events.get(0).get("chainPosition")).longValue();
        long middle = ((Number) events.get(1).get("chainPosition")).longValue();
        assertThat(newest).isGreaterThan(middle);

        mockMvc.perform(get("/api/v1/admin-api/audit-events").param("action", "CURSOR_EVENT")
                .param("beforePosition", String.valueOf(middle)).header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chainPosition").value(events.get(2).get("chainPosition")));
    }

    @Test
    @DisplayName("open quota and MCP surfaces validate params like the session endpoints")
    void openParamValidation() throws Exception {
        seedTenantOneSurface();
        mockMvc.perform(get("/api/v1/admin-api/mcp-access-logs").param("from", "2026-09-04T00:00:00Z")
                .param("to", "2026-09-03T00:00:00Z").header("Authorization", "Bearer mqk_admin_it_token_a"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
        mockMvc.perform(get("/api/v1/admin-api/mcp-access-logs").param("from", "not-an-instant").header("Authorization",
                "Bearer mqk_admin_it_token_a")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
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
