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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export and deletion endpoints against real PostgreSQL (G4.4): async CSV/JSONL
 * gzip export with SHA-256 download, and the double-confirmed deletion flow
 * with permanent audit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Export and deletion API integration tests (PostgreSQL)")
class ExportDeletionApiIntegrationTest {

    static final String MODEL = "claude-3-7-sonnet";

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
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    @Test
    @DisplayName("export produces a gzip CSV artifact with SHA-256 and bounded window")
    void exportProducesGzipCsv() throws Exception {
        fx.insertUsage("req-1", 1_000L, 500L);
        fx.insertUsage("req-2", 200L, 100L);

        MvcResult created = mockMvc.perform(post("/api/v1/admin/exports").param("format", "CSV")
                .param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-31T00:00:00Z")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isAccepted())
                .andReturn();
        String taskId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();

        // Poll until the task finishes (async executor, bounded window).
        String sha256 = null;
        byte[] artifact = null;
        for (int i = 0; i < 50; i++) {
            MvcResult statusResult = mockMvc.perform(get("/api/v1/admin/exports/" + taskId).cookie(sessionCookie))
                    .andExpect(status().isOk()).andReturn();
            Map<?, ?> body = objectMapper.readValue(statusResult.getResponse().getContentAsString(), Map.class);
            if ("SUCCEEDED".equals(body.get("status"))) {
                org.assertj.core.api.Assertions.assertThat(body.get("rowCount")).isEqualTo(2);
                MvcResult download = mockMvc
                        .perform(get("/api/v1/admin/exports/" + taskId + "/download").cookie(sessionCookie))
                        .andExpect(status().isOk())
                        .andExpect(header().string("X-MiQroKey-SHA256", (String) body.get("sha256"))).andReturn();
                artifact = download.getResponse().getContentAsByteArray();
                sha256 = (String) body.get("sha256");
                break;
            }
            Thread.sleep(100);
        }
        org.assertj.core.api.Assertions.assertThat(sha256).isNotNull();
        org.assertj.core.api.Assertions.assertThat(artifact).isNotEmpty();

        // Gunzip and verify the CSV contains both rows and only metadata columns.
        String csv = gunzip(artifact);
        org.assertj.core.api.Assertions.assertThat(csv).contains("req-1", "req-2", MODEL);
        org.assertj.core.api.Assertions.assertThat(csv).doesNotContain("sk-");
    }

    @Test
    @DisplayName("deletion previews, requires the one-time token and audits the execution")
    void deletionFlow() throws Exception {
        fx.insertUsage("req-1", 1_000L, 500L);
        fx.insertUsage("req-2", 200L, 100L);

        mockMvc.perform(get("/api/v1/admin/usage-deletions/preview").param("from", "2026-08-01T00:00:00Z")
                .param("to", "2026-08-31T00:00:00Z").cookie(sessionCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        MvcResult created = mockMvc.perform(post("/api/v1/admin/usage-deletions").param("from", "2026-08-01T00:00:00Z")
                .param("to", "2026-08-31T00:00:00Z").cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken)).andExpect(status().isOk()).andReturn();
        Map<?, ?> deletion = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        String deletionId = deletion.get("id").toString();
        String token = deletion.get("confirmToken").toString();

        // Wrong token is rejected.
        mockMvc.perform(
                post("/api/v1/admin/usage-deletions/" + deletionId + "/confirm").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("confirmToken", "wrong-token"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DELETION_TOKEN_INVALID"));

        // Correct token executes the deletion permanently.
        mockMvc.perform(
                post("/api/v1/admin/usage-deletions/" + deletionId + "/confirm").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("confirmToken", token))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.deletedCount").value(2));

        // Rows are gone and the audit trail exists.
        Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM usage_event WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", fx.tenantId), Long.class);
        org.assertj.core.api.Assertions.assertThat(remaining).isZero();
        Long audits = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_events WHERE action = 'USAGE_DELETE'",
                new MapSqlParameterSource(), Long.class);
        org.assertj.core.api.Assertions.assertThat(audits).isEqualTo(1L);

        // Confirming again is rejected (not confirmable twice).
        mockMvc.perform(
                post("/api/v1/admin/usage-deletions/" + deletionId + "/confirm").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("confirmToken", token))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELETION_NOT_CONFIRMABLE"));
    }

    @Test
    @DisplayName("anonymous access is rejected at the session layer")
    void anonymousForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/exports").param("limit", "10")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/usage-deletions/preview").param("from", "2026-08-01T00:00:00Z").param("to",
                "2026-08-31T00:00:00Z")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String gunzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            gzip.transferTo(out);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        void reset() {
            for (String table : List.of("export_tasks", "usage_deletions", "usage_event", "cache_hit_event",
                    "price_snapshot", "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                    "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships", "projects",
                    "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        void insertUsage(String providerRequestId, long input, long output) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, '00000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
                            :model, 'UPSTREAM', :input, :output, :total, 42, 200, TRUE, FALSE, 'greq', :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("model", MODEL)
                            .addValue("input", input).addValue("output", output).addValue("total", input + output)
                            .addValue("occurredAt", Timestamp.from(Instant.parse("2026-08-10T00:00:00Z"))));
        }
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
