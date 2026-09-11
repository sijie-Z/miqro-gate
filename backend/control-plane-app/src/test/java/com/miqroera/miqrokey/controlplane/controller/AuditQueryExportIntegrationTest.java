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
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit records query + compliance export (Tencent operation-records parity):
 * the enriched filters (targetType / actor / time window) and the CSV export
 * behave identically on the SYSTEM_ADMIN session endpoint and the open admin
 * machine endpoint ({@code usage:read}), stay tenant-scoped and never serialize
 * chain material.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Audit records query/export integration tests (PostgreSQL)")
class AuditQueryExportIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID FOREIGN_ACTOR = UUID.fromString("10000000-0000-0000-0000-0000000000ab");

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
    private UUID adminUserId;
    private final String adminUsername = "audit_" + UUID.randomUUID().toString().substring(0, 8);
    private String machineToken = "mqk_admin_audit_export_token";

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("admin_audit_events", "admin_api_keys", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
        jdbc.update("""
                INSERT INTO tenants (id, code, name) VALUES (:id, 'audit-it-b', 'Audit IT tenant B')
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
        insertMachineKey(TENANT_ID, "audit-exporter", machineToken);
        // Bootstrap itself writes audit events (login/password/register); drop
        // them so seed counts below are exact. chain_position sequence keeps
        // advancing — uniqueness is unaffected.
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("admin_audit_events", "admin_api_keys", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
    }

    // ------------------------------------------------------------- seeds

    private void insertMachineKey(UUID tenantId, String name, String token) {
        jdbc.update("""
                INSERT INTO admin_api_keys (id, tenant_id, name, key_digest, key_prefix, created_by, created_at)
                VALUES (:id, :tenantId, :name, :digest, :prefix, :createdBy, now())
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId).addValue("name", name)
                        .addValue("digest", digest(token)).addValue("prefix", "mqk_adm_it")
                        .addValue("createdBy", adminUserId));
    }

    private void seedAuditRow(UUID tenantId, UUID actorId, String action, String targetType, String summary,
            Instant createdAt) {
        seedAuditRow(tenantId, actorId, action, targetType, null, summary, createdAt);
    }

    private void seedAuditRow(UUID tenantId, UUID actorId, String action, String targetType, UUID targetId,
            String summary, Instant createdAt) {
        jdbc.update("""
                INSERT INTO admin_audit_events (id, tenant_id, actor_id, action, target_type, target_id,
                    change_summary, current_event_hash, created_at)
                VALUES (:id, :tenantId, :actorId, :action, :targetType, :targetId, CAST(:summary AS jsonb),
                    decode('00', 'hex'), :createdAt)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                        .addValue("actorId", actorId).addValue("action", action).addValue("targetType", targetType)
                        .addValue("targetId", targetId).addValue("summary", summary)
                        .addValue("createdAt", java.sql.Timestamp.from(createdAt)));
    }

    private void seedStandardSet() {
        Instant now = Instant.now();
        seedAuditRow(TENANT_ID, adminUserId, "SEED_USER_CREATE", "USER", "{\"name\":\"alice\"}",
                now.minusSeconds(3600 * 3));
        seedAuditRow(TENANT_ID, adminUserId, "SEED_MCP_SERVICE_CREATE", "MCP_SERVICE",
                "{\"name\":\"weather\", \"note\":\"has, comma and \\\"quote\\\"\"}", now.minusSeconds(3600 * 2));
        seedAuditRow(TENANT_ID, FOREIGN_ACTOR, "SEED_AGENT_DISABLE", "AGENT", "{\"name\":\"helper\"}",
                now.minusSeconds(1800));
        seedAuditRow(TENANT_ID, adminUserId, "SEED_MCP_SERVICE_CREATE", "MCP_SERVICE", "{\"name\":\"maps\"}",
                now.minusSeconds(60));
        // Foreign tenant row must never leak into either surface.
        seedAuditRow(OTHER_TENANT_ID, FOREIGN_ACTOR, "SEED_MCP_SERVICE_CREATE", "MCP_SERVICE", "{\"name\":\"foreign\"}",
                now);
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    // ------------------------------------------------------------- tests

    @Test
    @DisplayName("list: causal order + tenant isolation with no filters (human)")
    void listNoFiltersCausalAndTenantScoped() throws Exception {
        seedStandardSet();
        mockMvc.perform(get("/api/v1/admin/audit-events").cookie(sessionCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].action").value("SEED_MCP_SERVICE_CREATE"))
                .andExpect(jsonPath("$[0].targetType").value("MCP_SERVICE"))
                .andExpect(jsonPath("$[1].action").value("SEED_AGENT_DISABLE"))
                .andExpect(jsonPath("$[1].targetType").value("AGENT"))
                .andExpect(jsonPath("$[2].action").value("SEED_MCP_SERVICE_CREATE"))
                .andExpect(jsonPath("$[2].targetType").value("MCP_SERVICE"))
                .andExpect(jsonPath("$[3].action").value("SEED_USER_CREATE"))
                .andExpect(jsonPath("$[3].targetType").value("USER"));
    }

    @Test
    @DisplayName("list: every filter dimension applies, individually and combined (machine)")
    void listFiltersOnMachineEndpoint() throws Exception {
        seedStandardSet();
        String base = "/api/v1/admin-api/audit-events";
        // targetType narrows to MCP_SERVICE rows only (newest first).
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken).param("targetType", "MCP_SERVICE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].targetType").value("MCP_SERVICE"));
        // action narrows further.
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken)
                .param("action", "SEED_MCP_SERVICE_CREATE").param("targetType", "MCP_SERVICE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        // actor filter excludes the foreign-actor row.
        mockMvc.perform(
                get(base).header("Authorization", "Bearer " + machineToken).param("actorId", adminUserId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        // Time window keeps only the two most recent tenant rows.
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken).param("from",
                Instant.now().minusSeconds(3600).toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("SEED_MCP_SERVICE_CREATE"))
                .andExpect(jsonPath("$[1].action").value("SEED_AGENT_DISABLE"));
        // Combined window + action + targetType is the same slice as the human
        // endpoint.
        // 61 minutes back: safely inside the one-row-only "maps" bucket (weather
        // sits exactly 2h back) with no boundary equality.
        String from = Instant.now().minusSeconds(3660).toString();
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken)
                .param("action", "SEED_MCP_SERVICE_CREATE").param("targetType", "MCP_SERVICE").param("from", from))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeSummary").value(org.hamcrest.Matchers.containsString("maps")));
        // Cursor + filter combination walks the slice without overlap.
        MvcResult page1 = mockMvc
                .perform(get(base).header("Authorization", "Bearer " + machineToken).param("targetType", "MCP_SERVICE")
                        .param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andReturn();
        long cursor = objectMapper.readTree(page1.getResponse().getContentAsString()).get(0).get("chainPosition")
                .asLong();
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken).param("targetType", "MCP_SERVICE")
                .param("beforePosition", Long.toString(cursor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // Foreign tenant rows never surface.
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("list: invalid time parameters are rejected on both surfaces")
    void listRejectsInvalidTimeParams() throws Exception {
        seedStandardSet();
        mockMvc.perform(get("/api/v1/admin/audit-events").cookie(sessionCookie).header("X-CSRF-Token", csrfToken)
                .param("from", Instant.now().toString()).param("to", Instant.now().minusSeconds(60).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
        mockMvc.perform(get("/api/v1/admin-api/audit-events").header("Authorization", "Bearer " + machineToken)
                .param("from", "not-an-instant")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
    }

    @Test
    @DisplayName("export: CSV shape, quoting, tenant isolation and attachment headers (human)")
    void exportCsvShapeAndTenantIsolation() throws Exception {
        seedStandardSet();
        mockMvc.perform(
                get("/api/v1/admin/audit-events/export").cookie(sessionCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"audit-events-")))
                .andExpect(header().doesNotExist("X-MiQroKey-Truncated"));
        String csv = mockMvc.perform(
                get("/api/v1/admin/audit-events/export").cookie(sessionCookie).header("X-CSRF-Token", csrfToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith(String.valueOf((char) 0xFEFF))
                .contains("created_at,action,target_type," + "target_id,actor_id,change_summary,chain_position\n");
        // All four tenant rows + header; the foreign tenant row is absent.
        assertThat(csv.lines().count()).isEqualTo(5);
        assertThat(csv).doesNotContain("foreign");
        // Comma/quote-laden JSON summary is present in one properly formed data line.
        assertThat(csv).contains("has, comma and");
        // Every data row carries exactly one seeded action token (no split rows).
        assertThat(csv.lines().filter(line -> line.contains("SEED_")).count()).isEqualTo(4);
        // No chain material is ever serialized.
        assertThat(csv).doesNotContain("previous_event_hash").doesNotContain("current_event_hash");
    }

    @Test
    @DisplayName("export: machine endpoint shares filters; truncation is declared, not silent")
    void exportMachineFiltersAndTruncation() throws Exception {
        seedStandardSet();
        String base = "/api/v1/admin-api/audit-events/export";
        // Same filter shape as the list endpoint.
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken).param("targetType", "AGENT"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("X-MiQroKey-Truncated"));
        String filtered = mockMvc
                .perform(get(base).header("Authorization", "Bearer " + machineToken).param("targetType", "AGENT"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(filtered.lines().count()).isEqualTo(2);
        assertThat(filtered).doesNotContain("MCP_SERVICE");
        // Beyond the export cap the response declares truncation explicitly.
        jdbc.update("""
                INSERT INTO admin_audit_events (id, tenant_id, actor_id, action, target_type, change_summary,
                    current_event_hash, created_at)
                SELECT md5(i::text)::uuid, :tenantId, :actorId, 'BULK_SEED', 'BULK', '{}'::jsonb,
                    decode(repeat('00', 32), 'hex'), now()
                FROM generate_series(1, :count) AS i
                """, new MapSqlParameterSource("tenantId", TENANT_ID).addValue("actorId", adminUserId).addValue("count",
                50010));
        String truncated = mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken))
                .andExpect(status().isOk()).andExpect(header().string("X-MiQroKey-Truncated", "true")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(truncated.lines().count()).isEqualTo(50_001); // header + 50k rows
        // A narrow window on the same data exports fully without truncation.
        mockMvc.perform(get(base).header("Authorization", "Bearer " + machineToken).param("action", "BULK_SEED")
                .param("to", Instant.now().minusSeconds(3600).toString())).andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-MiQroKey-Truncated"));
    }

    @Test
    @DisplayName("list: target references resolve to resource names; unknown refs stay null (#389)")
    void listDecoratesTargetNames() throws Exception {
        seedStandardSet();
        // One real project + an audit row pointing at it: the list must carry
        // the resource name (read-side decoration, chain untouched).
        UUID projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:id, :tenantId, 'P-NAME', '名称解析目标', 'ACTIVE', 'core-ai', 0)
                """, new MapSqlParameterSource("id", projectId).addValue("tenantId", TENANT_ID));
        seedAuditRow(TENANT_ID, adminUserId, "SEED_PROJECT_CREATE", "PROJECT", projectId, "{\"code\":\"P-NAME\"}",
                Instant.now().minusSeconds(30));

        mockMvc.perform(get("/api/v1/admin/audit-events").cookie(sessionCookie).param("targetType", "PROJECT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].targetName").value("名称解析目标"));

        // Seeded MCP_SERVICE rows carry no target id: the page still renders and
        // the unresolved name stays null instead of failing the page.
        mockMvc.perform(get("/api/v1/admin/audit-events").cookie(sessionCookie).param("targetType", "MCP_SERVICE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].targetName").isEmpty());
    }
}
