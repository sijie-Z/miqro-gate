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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export task creation is a bulk extraction of the tenant's raw usage data: it
 * must leave an audit event naming the requesting admin, the requested window
 * and the format — on both the session surface and the open admin API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Export task audit tests (PostgreSQL)")
class AdminExportAuditIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_REQUEST_ID = "req-export-create-session";
    private static final String MACHINE_REQUEST_ID = "req-export-create-machine";
    private static final String DOWNLOAD_REQUEST_ID = "req-export-download-session";

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
    private final String adminUsername = "exp_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        clean();
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
        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername), UUID.class);
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : List.of("export_tasks", "admin_api_keys", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    @Test
    @DisplayName("session surface: creating an export task leaves an attributable audit event")
    void sessionExportCreationAudited() throws Exception {
        mockMvc.perform(post("/api/v1/admin/exports").param("format", "CSV").param("from", "2026-09-01T00:00:00Z")
                .param("to", "2026-09-02T00:00:00Z").cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .header("X-Request-Id", SESSION_REQUEST_ID)).andExpect(status().isAccepted());

        assertThat(countEvents("EXPORT_CREATE")).as("audit rows for EXPORT_CREATE").isEqualTo(1);
        Map<String, Object> event = latestEvent("EXPORT_CREATE");
        assertThat(event.get("actor_id")).isEqualTo(adminUserId);
        assertThat(event.get("admin_request_id")).isEqualTo(SESSION_REQUEST_ID);
        assertThat((String) event.get("summary")).contains("CSV").contains("2026-09-01");
    }

    @Test
    @DisplayName("machine surface: an open-admin-API export records the issuing admin and the key marker")
    void machineExportCreationAudited() throws Exception {
        String machineToken = "mqk_admin_export_audit_token";
        jdbc.update("""
                INSERT INTO admin_api_keys (id, tenant_id, name, key_digest, key_prefix, created_by, created_at)
                VALUES (:id, :tenantId, 'export-audit-key', :digest, 'mqk_adm_exp', :createdBy, now())
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT_ID)
                .addValue("digest", sha256(machineToken)).addValue("createdBy", adminUserId));

        mockMvc.perform(post("/api/v1/admin-api/export-tasks").param("format", "CSV")
                .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-02T00:00:00Z")
                .header("Authorization", "Bearer " + machineToken).header("X-Request-Id", MACHINE_REQUEST_ID))
                .andExpect(status().isAccepted());

        assertThat(countEvents("EXPORT_CREATE")).as("audit rows for EXPORT_CREATE").isEqualTo(1);
        Map<String, Object> event = latestEvent("EXPORT_CREATE");
        assertThat(event.get("actor_id")).isEqualTo(adminUserId);
        assertThat(event.get("admin_request_id")).isEqualTo(MACHINE_REQUEST_ID);
        assertThat((String) event.get("summary")).contains("admin-api:export-audit-key");
    }

    @Test
    @DisplayName("session surface: downloading an artifact leaves an attributable audit event")
    void sessionExportDownloadAudited() throws Exception {
        UUID taskId = createExportAndAwait();

        mockMvc.perform(get("/api/v1/admin/exports/" + taskId + "/download").cookie(sessionCookie, csrfCookie)
                .header("X-Request-Id", DOWNLOAD_REQUEST_ID)).andExpect(status().isOk());

        assertThat(countEvents("EXPORT_DOWNLOAD")).as("audit rows for EXPORT_DOWNLOAD").isEqualTo(1);
        Map<String, Object> event = latestEvent("EXPORT_DOWNLOAD");
        assertThat(event.get("actor_id")).isEqualTo(adminUserId);
        assertThat(event.get("admin_request_id")).isEqualTo(DOWNLOAD_REQUEST_ID);
        assertThat((String) event.get("summary")).contains("CSV");
    }

    /**
     * Creates an export through the session surface and waits for its async render
     * to land.
     */
    private UUID createExportAndAwait() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/exports").param("format", "CSV").param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z").cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfToken).header("X-Request-Id", SESSION_REQUEST_ID))
                .andExpect(status().isAccepted()).andReturn();
        UUID taskId = UUID.fromString(
                (String) objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id"));
        for (int i = 0; i < 100; i++) {
            String status = jdbc.queryForObject("SELECT status FROM export_tasks WHERE id = :id",
                    new MapSqlParameterSource("id", taskId), String.class);
            if ("SUCCEEDED".equals(status)) {
                return taskId;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("export task " + taskId + " did not reach SUCCEEDED within 10s");
    }

    private long countEvents(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    private Map<String, Object> latestEvent(String action) {
        return jdbc.queryForMap("""
                SELECT actor_id, change_summary::text AS summary, admin_request_id
                FROM admin_audit_events WHERE action = :action ORDER BY chain_position DESC LIMIT 1
                """, new MapSqlParameterSource("action", action));
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

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
