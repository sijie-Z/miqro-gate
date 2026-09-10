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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export reconcile level (#330, usage-accounting §11): the task-level
 * provider-request-id coverage is computed on completion, persisted, exposed on
 * the session and machine metadata surfaces and written into the artifact's
 * local_caliber_note column; an empty window declares nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Export reconcile level integration tests (PostgreSQL)")
class ExportReconcileLevelIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    private final String adminUsername = "rcl_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("export_tasks", "usage_event", "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", adminUsername), UUID.class);
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("export_tasks", "usage_event", "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
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

    private void seedUsage(int withProviderId, int withoutProviderId) {
        for (int i = 0; i < withProviderId + withoutProviderId; i++) {
            boolean withId = i < withProviderId;
            jdbc.update("""
                    INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                        provider_request_id, gateway_request_id, is_complete, usage_missing, occurred_at)
                    VALUES (:id, :tenantId, :keyId, :projectId, :productId, 'demo-model', :providerRequestId,
                        :gatewayRequestId, TRUE, FALSE, now() - interval '1 hour')
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT_ID)
                    .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                    .addValue("productId", UUID.randomUUID()).addValue("providerRequestId", withId ? "prov-" + i : null)
                    .addValue("gatewayRequestId", "gw-" + i));
        }
    }

    private String createAndAwaitExport() throws Exception {
        String from = java.time.Instant.now().minusSeconds(2 * 86400).toString();
        String to = java.time.Instant.now().toString();
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/exports").param("format", "CSV").param("from", from).param("to", to)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isAccepted()).andReturn();
        String taskId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        for (int i = 0; i < 40; i++) {
            String status = jdbc.queryForObject("SELECT status FROM export_tasks WHERE id = :id",
                    new MapSqlParameterSource("id", UUID.fromString(taskId)), String.class);
            if ("SUCCEEDED".equals(status)) {
                return taskId;
            }
            if ("FAILED".equals(status)) {
                throw new AssertionError("export failed");
            }
            Thread.sleep(250);
        }
        throw new AssertionError("export did not finish in time");
    }

    private String levelOf(String taskId) {
        return jdbc.queryForObject("SELECT reconcile_level FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(taskId)), String.class);
    }

    private String decompress(String taskId) throws Exception {
        byte[] gz = jdbc.queryForObject("SELECT file_bytes FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", UUID.fromString(taskId)), byte[].class);
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("levels: all provider-id, mixed, none, and empty window")
    void levelsAcrossCoverage() throws Exception {
        seedUsage(2, 0);
        String full = createAndAwaitExport();
        assertThat(levelOf(full)).isEqualTo("PROVIDER_ID_BACKED");
        assertThat(decompress(full)).contains("local-instant;reconcile=provider-id");

        jdbc.update("DELETE FROM usage_event", new MapSqlParameterSource());
        seedUsage(1, 1);
        String mixed = createAndAwaitExport();
        assertThat(levelOf(mixed)).isEqualTo("PARTIAL");
        assertThat(decompress(mixed)).contains("local-instant;reconcile=mixed");

        jdbc.update("DELETE FROM usage_event", new MapSqlParameterSource());
        seedUsage(0, 2);
        String local = createAndAwaitExport();
        assertThat(levelOf(local)).isEqualTo("LOCAL_ONLY");
        assertThat(decompress(local)).contains("local-instant;reconcile=local-only");

        jdbc.update("DELETE FROM usage_event", new MapSqlParameterSource());
        String empty = createAndAwaitExport();
        assertThat(levelOf(empty)).as("empty window declares nothing").isNull();
        String emptyCsv = decompress(empty);
        assertThat(emptyCsv.lines().count()).as("header only").isEqualTo(1);
        assertThat(emptyCsv).doesNotContain("reconcile=");
    }

    @Test
    @DisplayName("metadata surfaces carry the level (session list + task detail)")
    void metadataSurfaces() throws Exception {
        seedUsage(0, 1);
        String taskId = createAndAwaitExport();

        MvcResult list = mockMvc.perform(get("/api/v1/admin/exports").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"reconcileLevel\":\"LOCAL_ONLY\"");

        MvcResult task = mockMvc.perform(get("/api/v1/admin/exports/" + taskId).cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(task.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("LOCAL_ONLY");
    }
}
