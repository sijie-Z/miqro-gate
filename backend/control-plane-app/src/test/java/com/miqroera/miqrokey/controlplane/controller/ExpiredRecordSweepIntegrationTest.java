package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.service.ExpiredRecordSweeper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Expired-record GC (F06): the sweeper physically reclaims export artifacts
 * past their download window and deletion requests past their confirmation
 * window. EXECUTED deletion requests stay (permanent audit) and FAILED exports
 * stay visible for operations — both asserted here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Expired record sweep integration tests (PostgreSQL)")
class ExpiredRecordSweepIntegrationTest {

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
    ExpiredRecordSweeper sweeper;

    private UUID adminId;

    @BeforeEach
    void setUp() throws Exception {
        resetDb();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        adminId = UUID.fromString((String) bootBody.get("userId"));
    }

    @AfterEach
    void tearDown() {
        resetDb();
    }

    @Test
    @DisplayName("sweep removes finished exports past their window; keeps future, FAILED and PENDING rows")
    void sweepsExpiredExportsOnly() throws Exception {
        UUID expired = seedExport("SUCCEEDED", Instant.now().minus(2, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS));
        UUID fresh = seedExport("SUCCEEDED", Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        UUID failed = seedExport("FAILED", null, Instant.now().minus(40, ChronoUnit.DAYS));
        UUID pending = seedExport("PENDING", null, Instant.now().minus(40, ChronoUnit.DAYS));

        sweeper.sweep();

        assertThat(exportIds()).doesNotContain(expired).contains(fresh, failed, pending);
    }

    @Test
    @DisplayName("sweep removes expired deletion requests but keeps future and EXECUTED ones")
    void sweepsExpiredDeletionRequestsOnly() throws Exception {
        UUID pendingExpired = seedDeletion("PENDING_CONFIRMATION", Instant.now().minus(1, ChronoUnit.HOURS));
        UUID confirmedExpired = seedDeletion("CONFIRMED", Instant.now().minus(1, ChronoUnit.HOURS));
        UUID expiredState = seedDeletion("EXPIRED", Instant.now().minus(1, ChronoUnit.HOURS));
        UUID pendingFresh = seedDeletion("PENDING_CONFIRMATION", Instant.now().plus(1, ChronoUnit.HOURS));
        UUID executed = seedDeletion("EXECUTED", Instant.now().minus(10, ChronoUnit.DAYS));

        sweeper.sweep();

        assertThat(deletionIds()).doesNotContain(pendingExpired, confirmedExpired, expiredState).contains(pendingFresh,
                executed);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID tenantId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private UUID seedExport(String status, Instant expiresAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO export_tasks (id, tenant_id, created_by, format, period_from, period_to, status,
                    sha256, row_count, byte_count, file_bytes, created_at, finished_at, expires_at)
                VALUES (:id, :tenantId, :createdBy, 'CSV', :from, :to, :status, :sha256,
                    :rowCount, :bytes, :payload::bytea, :createdAt, :finishedAt, :expiresAt)
                """,
                new MapSqlParameterSource("id", id).addValue("tenantId", tenantId()).addValue("createdBy", adminId)
                        .addValue("from", java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)))
                        .addValue("to", java.sql.Timestamp.from(Instant.now().minus(29, ChronoUnit.DAYS)))
                        .addValue("status", status).addValue("sha256", "a".repeat(64)).addValue("rowCount", 1L)
                        .addValue("bytes", 4L).addValue("payload", "\\x01020304")
                        .addValue("createdAt", java.sql.Timestamp.from(createdAt))
                        .addValue("finishedAt", java.sql.Timestamp.from(createdAt))
                        .addValue("expiresAt", expiresAt != null ? java.sql.Timestamp.from(expiresAt) : null));
        return id;
    }

    private UUID seedDeletion(String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usage_deletions (id, tenant_id, requested_by, period_from, period_to, preview_count,
                    confirm_token_hash, status, deleted_count, executed_at, expires_at, created_at)
                VALUES (:id, :tenantId, :requestedBy, :from, :to, :preview, :hash, :status, :deletedCount,
                    :executedAt, :expiresAt, :createdAt)
                """,
                new MapSqlParameterSource("id", id).addValue("tenantId", tenantId()).addValue("requestedBy", adminId)
                        .addValue("from", java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)))
                        .addValue("to", java.sql.Timestamp.from(Instant.now().minus(29, ChronoUnit.DAYS)))
                        .addValue("preview", 5L).addValue("hash", new byte[]{1, 2, 3}).addValue("status", status)
                        .addValue("deletedCount", "EXECUTED".equals(status) ? 5L : null)
                        .addValue("executedAt",
                                "EXECUTED".equals(status)
                                        ? java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS))
                                        : null)
                        .addValue("expiresAt", java.sql.Timestamp.from(expiresAt))
                        .addValue("createdAt", java.sql.Timestamp.from(expiresAt.minus(1, ChronoUnit.HOURS))));
        return id;
    }

    private List<UUID> exportIds() {
        return jdbc.query("SELECT id FROM export_tasks", new MapSqlParameterSource(),
                (rs, i) -> (UUID) rs.getObject(1));
    }

    private List<UUID> deletionIds() {
        return jdbc.query("SELECT id FROM usage_deletions", new MapSqlParameterSource(),
                (rs, i) -> (UUID) rs.getObject(1));
    }

    private void resetDb() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "usage_deletions", "export_tasks", "usage_event", "price_snapshot", "quota_rules",
                "quota_default_template", "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "upstream_credential_versions",
                "upstream_credentials", "plan_seats", "upstream_subscriptions", "project_memberships", "projects",
                "provider_products", "providers", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Child-first order above covers the canonical FK set.
            }
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
