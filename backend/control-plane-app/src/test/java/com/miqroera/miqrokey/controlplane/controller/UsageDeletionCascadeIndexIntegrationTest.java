package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.service.UsageDeletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * usage_event 的删除路径必须能用索引满足 usage_adjustments 的外键检查。
 *
 * <p>
 * {@code usage_adjustments.usage_event_id REFERENCES usage_event (id) ON DELETE
 * CASCADE}（V63）让 PostgreSQL 为 <b>每一条</b> 被删的 usage_event 行执行一次
 * {@code DELETE FROM ONLY usage_adjustments WHERE usage_event_id = $1}
 * （{@code RI_FKey_cascade_del}）。该语句只有 {@code usage_event_id} 一个等值条件，而 V63 建的索引是
 * {@code (tenant_id, usage_event_id)} —— 前导列不是外键列，因此索引无法被使用， PostgreSQL
 * 只能退化为逐行全表扫描 usage_adjustments。
 * </p>
 *
 * <p>
 * 同一个删除路径上还有第二个同样形状的缺口：级联删除 adjustment 行时，
 * {@code usage_adjustments.reversal_of_id REFERENCES usage_adjustments (id) ON
 * DELETE RESTRICT} 会为 <b>每一条</b> 被删的 adjustment 行执行一次
 * {@code ... WHERE reversal_of_id = $1 FOR KEY SHARE OF x}，而 {@code
 * reversal_of_id} 上没有任何索引。因此判据用「删除路径整体的 seq_scan 增量」而不是 只盯一个外键。
 * </p>
 *
 * <p>
 * 本测试用 {@code pg_stat_user_tables.seq_scan} 计数（而非耗时）判定： 删除 N 行 usage_event 不允许对
 * usage_adjustments 触发 N 次顺序扫描。计数前先用 一次刻意的全表扫描验证统计通道本身是通的，避免统计未刷新导致的假绿。
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("Usage deletion cascade index integration tests (PostgreSQL)")
class UsageDeletionCascadeIndexIntegrationTest {

    private static final UUID TENANT = UUID.fromString("1e000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("1e000000-0000-4000-8000-0000000000a1");

    /** Number of usage_event rows deleted by the confirmed deletion request. */
    private static final int WINDOW_EVENTS = 2_000;
    /** usage_event rows outside the window: 只作为 usage_adjustments 的外键宿主。 */
    private static final int OTHER_EVENTS = 18_000;

    private static final Instant WINDOW_FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant OUTSIDE = Instant.parse("2026-07-01T00:00:00Z");

    private static final java.nio.file.Path SECRET_FILE;
    private static final String SECRET = "test-bootstrap-secret-min-16chars";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
        try {
            SECRET_FILE = java.nio.file.Files.createTempFile("bootstrap-secret", ".txt");
            java.nio.file.Files.writeString(SECRET_FILE, SECRET);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> SECRET_FILE.toAbsolutePath().toString());
    }

    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    UsageDeletionService deletions;

    @BeforeEach
    void setUp() {
        reset();
        seed();
    }

    @AfterEach
    void tearDown() {
        reset();
    }

    @Test
    @DisplayName("删除 2000 行 usage_event 不得对 usage_adjustments 逐行全表扫描")
    void deletionDoesNotSeqScanAdjustmentsPerRow() {
        long probeBaseline = seqScans("usage_adjustments");
        forceSeqScanOnAdjustments();
        long probeDelta = awaitSeqScanDelta(probeBaseline, 1, Duration.ofSeconds(10));
        assertThat(probeDelta).as("pg_stat_user_tables 统计通道必须可见，否则本测试无判定力（precondition）").isGreaterThanOrEqualTo(1);
        long baseline = probeBaseline + probeDelta;

        long deleted = deleteWindowEvents();
        assertThat(deleted).as("确认删除必须真的删掉窗口内的行").isEqualTo(WINDOW_EVENTS);

        long delta = awaitSeqScanDelta(baseline, WINDOW_EVENTS, Duration.ofSeconds(10));
        assertThat(delta).as("删除 %d 行 usage_event：usage_adjustments 的 seq_scan 增量必须远小于删除行数"
                + "（usage_event_id 与 reversal_of_id 两个外键的检查查询都必须走索引）", WINDOW_EVENTS).isLessThan(WINDOW_EVENTS);
    }

    @Test
    @DisplayName("外键检查查询的实执行计划不得是 usage_adjustments 全表扫描")
    void foreignKeyCheckQueryUsesIndex() {
        String id = jdbc.queryForObject("SELECT id::text FROM usage_event WHERE occurred_at >= :from LIMIT 1",
                new MapSqlParameterSource("from", java.sql.Timestamp.from(WINDOW_FROM)), String.class);
        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT 1 FROM ONLY usage_adjustments x
                 WHERE usage_event_id = :id::uuid
                   FOR KEY SHARE OF x
                """, new MapSqlParameterSource("id", id), String.class));

        assertThat(plan).as("usage_adjustments_usage_event_id_fkey 的检查语句只带 usage_event_id 这一个等值条件"
                + "（CASCADE 侧真实语句是 DELETE FROM ONLY usage_adjustments WHERE usage_event_id = $1，"
                + "本探针用同形状的锁行查询代替），它必须能走 usage_event_id 前导的索引").doesNotContain("Seq Scan");
    }

    @Test
    @DisplayName("自引用外键检查查询的实执行计划不得是 usage_adjustments 全表扫描")
    void reversalForeignKeyCheckQueryUsesIndex() {
        String id = jdbc.queryForObject("SELECT id::text FROM usage_adjustments LIMIT 1", new MapSqlParameterSource(),
                String.class);
        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT 1 FROM ONLY usage_adjustments x
                 WHERE reversal_of_id = :id::uuid
                   FOR KEY SHARE OF x
                """, new MapSqlParameterSource("id", id), String.class));

        assertThat(plan).as("PostgreSQL 为外键 usage_adjustments_reversal_of_id_fkey（ON DELETE RESTRICT）"
                + "执行的正是这条查询，它必须能走 reversal_of_id 前导的索引").doesNotContain("Seq Scan");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long deleteWindowEvents() {
        UsageDeletionService.DeletionRequest request = deletions.create(TENANT, ADMIN, WINDOW_FROM, WINDOW_TO);
        return deletions.confirm(TENANT, request.id(), request.confirmToken()).deletedCount();
    }

    /**
     * Deliberate scan so that the stats channel is proven live before it is used as
     * evidence.
     */
    private void forceSeqScanOnAdjustments() {
        jdbc.queryForObject("SELECT count(*) FROM usage_adjustments WHERE reason = 'ph27-never-matches'",
                new MapSqlParameterSource(), Long.class);
    }

    private long seqScans(String table) {
        Long value = jdbc.queryForObject("SELECT seq_scan FROM pg_stat_user_tables WHERE relname = :t",
                new MapSqlParameterSource("t", table), Long.class);
        return value == null ? 0L : value;
    }

    /**
     * Polls until the cumulative seq_scan counter of {@code table} moved by at
     * least {@code target} since {@code baseline}, or the timeout elapses. Returns
     * the delta observed at that point — callers assert on it, so a timeout in the
     * "already fixed" case yields the small delta we expect.
     */
    private long awaitSeqScanDelta(long baseline, long target, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long delta = seqScans("usage_adjustments") - baseline;
        while (delta < target && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            // Backend stats are reported at transaction end / when the backend goes
            // idle; nudge the reporting so the counter is visible within the timeout.
            jdbc.queryForObject("SELECT pg_stat_force_next_flush() IS NULL", new MapSqlParameterSource(),
                    Boolean.class);
            jdbc.queryForObject("SELECT 1", new MapSqlParameterSource(), Integer.class);
            delta = seqScans("usage_adjustments") - baseline;
        }
        return delta;
    }

    private void seed() {
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                VALUES (:id, 'ph27-fk', 'PH27 FK Index', 'ACTIVE', 0, now(), now())
                """, new MapSqlParameterSource("id", TENANT));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                    must_change_password, failed_login_count, version, created_at, updated_at)
                VALUES (:id, :tenantId, 'ph27-fk-admin', 'PH27 Admin', 'x', 'SYSTEM_ADMIN', 'ACTIVE',
                    false, 0, 0, now(), now())
                """, new MapSqlParameterSource("id", ADMIN).addValue("tenantId", TENANT));

        // Window rows (deleted by the test) and out-of-window rows (FK hosts only).
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, provider_request_id, virtual_key_id, project_id,
                    provider_product_id, model_id, cache_level, input_tokens, output_tokens, total_tokens,
                    latency_ms, upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                SELECT md5('ph27-fk-win-' || i)::uuid, :tenantId, 'ph27-fk-win-' || i,
                       '1e000000-0000-4000-8000-0000000000f1', '1e000000-0000-4000-8000-0000000000f2',
                       '1e000000-0000-4000-8000-0000000000f3', 'ph27-model', 'UPSTREAM', 1, 1, 2,
                       42, 200, TRUE, FALSE, 'ph27-fk-gw-win-' || i, :occurredAt
                  FROM generate_series(1, :count) i
                """, new MapSqlParameterSource("tenantId", TENANT).addValue("count", WINDOW_EVENTS)
                .addValue("occurredAt", java.sql.Timestamp.from(WINDOW_FROM.plusSeconds(3600))));
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, provider_request_id, virtual_key_id, project_id,
                    provider_product_id, model_id, cache_level, input_tokens, output_tokens, total_tokens,
                    latency_ms, upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                SELECT md5('ph27-fk-out-' || i)::uuid, :tenantId, 'ph27-fk-out-' || i,
                       '1e000000-0000-4000-8000-0000000000f1', '1e000000-0000-4000-8000-0000000000f2',
                       '1e000000-0000-4000-8000-0000000000f3', 'ph27-model', 'UPSTREAM', 1, 1, 2,
                       42, 200, TRUE, FALSE, 'ph27-fk-gw-out-' || i, :occurredAt
                  FROM generate_series(1, :count) i
                """, new MapSqlParameterSource("tenantId", TENANT).addValue("count", OTHER_EVENTS)
                .addValue("occurredAt", java.sql.Timestamp.from(OUTSIDE)));

        // One adjustment per row, so the FK check has a realistic table to scan.
        jdbc.update("""
                INSERT INTO usage_adjustments (id, tenant_id, usage_event_id, adjustment_type,
                    input_tokens_delta, reason, created_at)
                SELECT md5('ph27-fk-adj-win-' || i)::uuid, :tenantId, md5('ph27-fk-win-' || i)::uuid,
                       'USAGE', 10, 'ph27 synthetic correction', now()
                  FROM generate_series(1, :count) i
                """, new MapSqlParameterSource("tenantId", TENANT).addValue("count", WINDOW_EVENTS));
        jdbc.update("""
                INSERT INTO usage_adjustments (id, tenant_id, usage_event_id, adjustment_type,
                    input_tokens_delta, reason, created_at)
                SELECT md5('ph27-fk-adj-out-' || i)::uuid, :tenantId, md5('ph27-fk-out-' || i)::uuid,
                       'USAGE', 10, 'ph27 synthetic correction', now()
                  FROM generate_series(1, :count) i
                """, new MapSqlParameterSource("tenantId", TENANT).addValue("count", OTHER_EVENTS));

        // The planner needs real statistics; without them a 20k-row table still
        // looks like "a few pages" and the seq-scan choice would be an artefact.
        jdbc.getJdbcTemplate().execute("ANALYZE usage_adjustments");
        jdbc.getJdbcTemplate().execute("ANALYZE usage_event");
    }

    /**
     * Removes only the rows this test owns, in child-first order.
     *
     * <p>
     * The container is a static singleton shared by every control-plane integration
     * test in the JVM ({@link AbstractControlPlaneIntegrationTest}) and Flyway
     * migrates it exactly once, so the tenant seeded by {@code V1__core_tables.sql}
     * ({@code 00000000-0000-0000-0000-000000000001}) can never come back once it is
     * deleted. The bootstrap endpoint locks precisely that row and requires it to
     * exist ({@code UserRepositoryImpl.lockTenantForBootstrap}:
     * {@code SELECT id FROM tenants WHERE id
     * = :id FOR UPDATE}, exactly one row expected), so an unscoped
     * {@code DELETE FROM tenants} turns the {@code POST /api/v1/auth/bootstrap} of
     * <b>every</b> test class that runs after this one into a 500. Every statement
     * below is therefore scoped to {@link #TENANT}.
     * </p>
     */
    private void reset() {
        MapSqlParameterSource tenant = new MapSqlParameterSource("tenantId", TENANT);
        for (String table : List.of("usage_adjustments", "usage_deletions", "export_tasks", "usage_event",
                "admin_audit_events", "users")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = :tenantId", tenant);
        }
        jdbc.update("DELETE FROM tenants WHERE id = :tenantId", tenant);
    }
}
