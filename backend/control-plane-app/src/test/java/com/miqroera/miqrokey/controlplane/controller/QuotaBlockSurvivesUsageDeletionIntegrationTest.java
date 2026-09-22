package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.UpsertQuotaRuleRequest;
import com.miqroera.miqrokey.controlplane.service.AdminQuotaRuleService;
import com.miqroera.miqrokey.controlplane.service.QuotaEnforcementService;
import com.miqroera.miqrokey.controlplane.service.UsageDeletionService;
import com.miqroera.miqrokey.domain.model.QuotaAction;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量删除不得悄悄解封被 REJECT 配额规则拦住的 scope。
 *
 * <p>
 * 机制链条：{@code QuotaEnforcementService.evaluate()} 每 60 秒用
 * {@code QuotaWatermarks.evaluate()} <b>实时</b> 聚合 {@code usage_event}
 * （{@code AdminUsageStatsService.summaryObservedUncapped} →
 * {@code UsageStatsRepository.aggregateObservedUsage}），把仍然 EXCEEDED 的 REJECT
 * 规则写进 {@code quota_enforcement}；网关侧 {@code JdbcRouteSnapshotLoader} 读该表 生成
 * {@code quotaBlockedUsers}，{@code QuotaGate} 据此返回 429 quota_exceeded。 于是
 * {@code UsageDeletionService.confirm()} 物理删掉当前窗口内的 {@code usage_event}
 * 行之后，下一次评估算出的 watermark 归零， 规则不再 EXCEEDED → 该 scope 从
 * {@code quota_enforcement} 消失 → 网关停止 429。
 * </p>
 *
 * <p>
 * 文档与领域模型对这条 429 的承诺是「直到窗口结束、或者管理员提高限额才解除」 （{@code QuotaAction.REJECT} /
 * {@code QuotaRule} javadoc、{@code QuotaGate}），
 * 用法删除完全不在解除条件里——管理员以为自己在做数据保留清理，实际后果是把 超限流量重新放行。 本测试断言该承诺成立：删除之后 block 必须还在。
 * </p>
 *
 * <p>
 * 规则用 PROJECT scope：USER scope 的聚合要 JOIN virtual_keys 才能按 user 过滤，PROJECT scope
 * 的谓词直接落在 {@code usage_event.project_id} 上。两者走的是同一条 watermark → verdict →
 * {@code quota_enforcement} → 网关快照 的链路，本测试只关心该链路 的输入（窗口内用量）被删除后的结果。
 * </p>
 *
 * <p>
 * <b>怎么读这 7 个用例</b>（对抗评审第 4 轮点名要写清楚，免得后续读者把「7 绿」读成「7 个都在证红」）：
 * {@link #usageDeletionMustNotLiftAnExceededQuotaBlock()}（第一轮）、
 * {@link #usageDeletionInTheNextWindowStillCannotLiftAContinuousBlock()}（第二轮）与
 * {@link #adminEditThatKeepsTheRuleExceededStillSurvivesTheDeletion()}（第四轮）是<b>判别性</b>用例——
 * 三者分别在各自那一轮的改前代码上会红（红证据见 PH67_report.md §2.3 / §2.8.2 / §2.11.2）；其余四个
 * （窗口过期即解除、提额即解除、跨窗口刷新 {@code window_end}、提额到读数之上即解除）在改前也是绿的，
 * 作用是<b>回归护栏</b>——钉住这条修复线不许走向反面，把该放的流量关死。
 * </p>
 *
 * <p>
 * {@link #staleVerdictIsDroppedOnceItsWindowHasEnded()}、
 * {@link #recordedWindowEndAdvancesWhileTheRuleStaysExceeded()} 与
 * {@link #usageDeletionInTheNextWindowStillCannotLiftAContinuousBlock()} 需要
 * {@code observed_used} 这一列（V76），在改前的 schema 上跑不起来——它们不是"改前会红"，是"改前不存在"。
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("Quota block vs usage deletion integration tests (PostgreSQL)")
class QuotaBlockSurvivesUsageDeletionIntegrationTest {

    private static final UUID TENANT = UUID.fromString("1e000000-0000-4000-8000-00000000c067");
    private static final UUID ADMIN = UUID.fromString("1e000000-0000-4000-8000-00000000a067");
    private static final UUID RULE = UUID.fromString("1e000000-0000-4000-8000-00000000c068");
    /**
     * The project the rule blocks and the project the seeded usage is attributed
     * to.
     */
    private static final UUID PROJECT = UUID.fromString("1e000000-0000-4000-8000-0000000000f2");

    /** Rule limit in tokens; the seeded window usage is deliberately above it. */
    private static final long LIMIT_TOKENS = 1_000L;
    /**
     * Events inside the deletion window, 400 tokens each → 2000 observed tokens.
     */
    private static final int WINDOW_EVENTS = 5;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
    }

    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    QuotaEnforcementService quotaEnforcement;
    @Autowired
    UsageDeletionService deletions;
    @Autowired
    QuotaRuleRepository quotaRules;
    @Autowired
    AdminQuotaRuleService adminQuotaRules;

    @BeforeEach
    void setUp() {
        reset();
        seedAdmin();
        seedProject();
        seedRejectRule();
        seedUsage();
    }

    @AfterEach
    void tearDown() {
        reset();
    }

    @Test
    @DisplayName("删除当前窗口的 usage_event 之后，EXCEEDED 的 REJECT 规则必须继续拦着该 scope")
    void usageDeletionMustNotLiftAnExceededQuotaBlock() {
        // 1. Precondition: the evaluator derives EXCEEDED (2000 / 1000 tokens) and
        // blocks the scope — this is the state the gateway answers 429 from.
        quotaEnforcement.evaluate();
        long blockedBefore = blockedRuleRows();
        System.out.println("[PH67] window usage_event rows=" + windowEventCount() + " window tokens=" + windowTokens()
                + " (limit " + LIMIT_TOKENS + ") block rows after 1st evaluate=" + blockedBefore);
        assertThat(blockedBefore).as("precondition: 超限的 REJECT 规则必须已经拦住该 scope").isEqualTo(1);

        // 2. The documented admin deletion path: preview + confirm over the current
        // quota window (today, UTC — the same slice QuotaWatermarks reads).
        Instant from = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = Instant.now().plusSeconds(1);
        UsageDeletionService.DeletionRequest request = deletions.create(TENANT, ADMIN, from, to);
        long deleted = deletions.confirm(TENANT, request.id(), request.confirmToken()).deletedCount();
        System.out.println("[PH67] block row before deletion=" + blockRows() + " | deletion window=" + from + ".." + to
                + " deleted=" + deleted + " window usage_event rows left=" + windowEventCount() + " tokens left="
                + windowTokens());
        assertThat(deleted).as("删除必须真的删掉窗口内的行（否则本测试无判定力）").isEqualTo(WINDOW_EVENTS);

        // 3. Next enforcement cycle. The 429 promise is "until the window rolls over
        // or the admin raises the limit" — a retention deletion is neither.
        quotaEnforcement.evaluate();
        long blockedAfter = blockedRuleRows();
        System.out.println("[PH67] block rows after 2nd evaluate=" + blockedAfter + " detail=" + blockRows());

        assertThat(blockedAfter)
                .as("管理员删除用量后，被 REJECT 规则拦住的 scope 不得被自动解封：" + "quota_enforcement 里必须仍然有这条规则（网关的 429 只由该表驱动）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("窗口滚过之后判定必须消失：卡住的 block 不能靠粘性判定永久拦住流量")
    void staleVerdictIsDroppedOnceItsWindowHasEnded() {
        // The new window holds no usage at all, and what sits in the table is the
        // verdict recorded for the previous one.
        jdbc.update("DELETE FROM usage_event WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", TENANT));
        seedRecordedBlock(Instant.now().minusSeconds(3_600), Instant.now().minusSeconds(7_200));
        assertThat(blockedRuleRows()).as("precondition: 上一轮的判定行还在表里").isEqualTo(1);

        quotaEnforcement.evaluate();

        assertThat(blockedRuleRows()).as("window_end 已过 + 当前窗口未超限 → 判定必须消失，否则被拦的 scope 永远解不开").isZero();
    }

    @Test
    @DisplayName("管理员提高限额之后判定必须消失：粘性判定不得剥夺文档承诺的恢复路径")
    void raisingTheLimitLiftsTheRecordedBlock() {
        quotaEnforcement.evaluate();
        assertThat(blockedRuleRows()).as("precondition: 超限的 REJECT 规则必须已经拦住该 scope").isEqualTo(1);
        // Compress the timeline: the verdict was recorded an hour ago, so the admin
        // edit below is unambiguously later than it (the escape compares the two).
        jdbc.update("UPDATE quota_enforcement SET blocked_at = now() - interval '1 hour' WHERE rule_id = :rule",
                new MapSqlParameterSource("rule", RULE));

        QuotaRule raised = quotaRules.findById(TENANT, RULE).orElseThrow();
        quotaRules.upsert(new QuotaRule(raised.id(), raised.tenantId(), raised.scopeType(), raised.scopeId(),
                raised.metric(), raised.period(), raised.action(), 5_000L, raised.warnPercent(), raised.status(),
                raised.createdBy(), raised.version() + 1, raised.createdAt(), Instant.now()));

        quotaEnforcement.evaluate();

        assertThat(blockedRuleRows()).as("限额提到 5000、窗口内用量 2000 → 判定必须消失").isZero();
    }

    @Test
    @DisplayName("跨窗口仍超限时，记录里的 window_end 必须前进到当前窗口（#1316 的第二窗口）")
    void recordedWindowEndAdvancesWhileTheRuleStaysExceeded() {
        // What the table holds when a rule stays exceeded across a window rollover: the
        // verdict of the window that just ended, while the live reading is taken in the
        // current one and still says EXCEEDED.
        ageRule();
        seedRecordedBlock(Instant.now().minusSeconds(600), Instant.now().minusSeconds(3_600));

        quotaEnforcement.evaluate();

        System.out.println("[PH67] rollover: block row=" + blockRows() + " (rule still exceeded)");
        assertThat(blockedWindowEnd())
                .as("规则仍然超限 → 记录必须跟上当前窗口；冻结在上一窗口的 window_end 一滚过去，" + "下一次水位下跌（例如管理员删用量）就会把判定当成过期丢掉")
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("跨窗口持续超限之后再删当前窗口用量：block 必须仍然在（#1316 的第二窗口）")
    void usageDeletionInTheNextWindowStillCannotLiftAContinuousBlock() {
        ageRule();
        seedRecordedBlock(Instant.now().minusSeconds(600), Instant.now().minusSeconds(3_600));
        quotaEnforcement.evaluate();
        assertThat(blockedRuleRows()).as("precondition: 仍超限的规则必须继续留在表里").isEqualTo(1);

        Instant from = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = Instant.now().plusSeconds(1);
        UsageDeletionService.DeletionRequest request = deletions.create(TENANT, ADMIN, from, to);
        long deleted = deletions.confirm(TENANT, request.id(), request.confirmToken()).deletedCount();
        assertThat(deleted).as("删除必须真的删掉窗口内的行（否则本测试无判定力）").isEqualTo(WINDOW_EVENTS);

        quotaEnforcement.evaluate();
        System.out.println(
                "[PH67] 2nd window: block rows after deletion=" + blockedRuleRows() + " detail=" + blockRows());

        assertThat(blockedRuleRows()).as("窗口滚过一次之后删用量同样是'删除即解封'——#1316 在第二窗口原样复现").isEqualTo(1);
    }

    @Test
    @DisplayName("管理员改限额但改完仍超限、随后删用量：block 必须仍然在（#1316 的'改动过规则'变体）")
    void adminEditThatKeepsTheRuleExceededStillSurvivesTheDeletion() {
        quotaEnforcement.evaluate();
        assertThat(blockedRuleRows()).as("precondition: 超限的 REJECT 规则必须已经拦住该 scope").isEqualTo(1);
        // Compress the timeline so the save below is unambiguously later than the
        // recorded
        // verdict — that ordering is what arms the timestamp-based "the admin edited
        // the
        // rule" escape, and the escape must not be what decides this case.
        jdbc.update("UPDATE quota_enforcement SET blocked_at = now() - interval '1 hour' WHERE rule_id = :rule",
                new MapSqlParameterSource("rule", RULE));

        // The admin's move, through the real admin service: 1000 -> 1500 tokens while
        // the window already holds 2000. put() stamps updated_at = Instant.now() on
        // every save. Raising a limit that still leaves the scope over it is not one of
        // ADR-0020's two recovery paths, so the block has to stay.
        adminQuotaRules
                .put(TENANT, ADMIN,
                        new UpsertQuotaRuleRequest(QuotaScopeType.PROJECT, PROJECT, QuotaMetric.TOKENS,
                                QuotaPeriod.DAILY, 1_500L, 80, QuotaRuleStatus.ACTIVE, QuotaAction.REJECT),
                        "ph67-f1-edit");
        assertThat(windowTokens()).as("precondition: 改完限额之后规则仍然超限（1500 < 2000）").isGreaterThan(1_500L);
        System.out.println("[PH67] after admin edit (limit 1500, still exceeded): block row=" + blockRows());

        // Deliberately no enforcement cycle between the save and the deletion. The save
        // alone says nothing about whether the rule is still exceeded, and the
        // evaluator
        // only runs every 60s — so "save → retention deletion → next cycle" is a
        // reachable
        // order, and the verdict must survive it. (An intervening cycle here would hide
        // the defect: the re-derivation would re-anchor the escape before the deletion
        // fires it.)
        Instant from = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = Instant.now().plusSeconds(1);
        UsageDeletionService.DeletionRequest request = deletions.create(TENANT, ADMIN, from, to);
        long deleted = deletions.confirm(TENANT, request.id(), request.confirmToken()).deletedCount();
        assertThat(deleted).as("删除必须真的删掉窗口内的行（否则本测试无判定力）").isEqualTo(WINDOW_EVENTS);

        quotaEnforcement.evaluate();
        System.out.println("[PH67] after admin edit + deletion (no cycle in between): block rows=" + blockedRuleRows()
                + " detail=" + blockRows());

        assertThat(blockedRuleRows()).as("这次改动不是恢复路径（改完仍然超限），不得把随后的用量删除变成解封：" + "判定必须仍在（网关的 429 只由该表驱动）").isEqualTo(1);
    }

    @Test
    @DisplayName("管理员把限额提到记录读数之上、随后删用量：判定必须消失（恢复路径不得被粘性判定吃掉）")
    void adminRaiseAboveTheLastReadingLiftsTheBlockAfterADeletion() {
        quotaEnforcement.evaluate();
        assertThat(blockedRuleRows()).as("precondition: 超限的 REJECT 规则必须已经拦住该 scope").isEqualTo(1);
        jdbc.update("UPDATE quota_enforcement SET blocked_at = now() - interval '1 hour' WHERE rule_id = :rule",
                new MapSqlParameterSource("rule", RULE));

        // 1000 -> 5000 with 2000 tokens already spent: this *is* ADR-0020's second
        // recovery
        // path (the admin raised the limit), so the block must let go — the sticky
        // verdict
        // may not turn a real recovery into a trap. Same order as the test above (no
        // enforcement cycle between the save and the deletion), so the pair pins the
        // decision on the recorded reading rather than on the timing of the save.
        adminQuotaRules
                .put(TENANT, ADMIN,
                        new UpsertQuotaRuleRequest(QuotaScopeType.PROJECT, PROJECT, QuotaMetric.TOKENS,
                                QuotaPeriod.DAILY, 5_000L, 80, QuotaRuleStatus.ACTIVE, QuotaAction.REJECT),
                        "ph67-f1-raise");

        Instant from = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = Instant.now().plusSeconds(1);
        UsageDeletionService.DeletionRequest request = deletions.create(TENANT, ADMIN, from, to);
        long deleted = deletions.confirm(TENANT, request.id(), request.confirmToken()).deletedCount();
        assertThat(deleted).as("删除必须真的删掉窗口内的行（否则本测试无判定力）").isEqualTo(WINDOW_EVENTS);

        quotaEnforcement.evaluate();
        System.out.println("[PH67] after admin raise above the recorded reading + deletion: block rows="
                + blockedRuleRows() + " detail=" + blockRows());

        assertThat(blockedRuleRows()).as("限额 5000 > 记录读数 2000 → 管理员确实解除了这次超限，判定必须消失").isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The rule was last touched two hours ago: the admin-edit escape must not fire
     * here.
     */
    private void ageRule() {
        jdbc.update("UPDATE quota_rules SET updated_at = now() - interval '2 hours' WHERE id = :rule",
                new MapSqlParameterSource("rule", RULE));
    }

    /**
     * The window end the recorded verdict carries, i.e. when the block lets go by
     * itself.
     */
    private Instant blockedWindowEnd() {
        Timestamp end = jdbc.queryForObject("SELECT window_end FROM quota_enforcement WHERE rule_id = :rule",
                new MapSqlParameterSource("rule", RULE), Timestamp.class);
        return end == null ? null : end.toInstant();
    }

    /** A verdict row as the previous enforcement cycle would have left it. */
    private void seedRecordedBlock(Instant windowEnd, Instant blockedAt) {
        seedRecordedBlock(windowEnd, blockedAt, BigDecimal.valueOf(2_000L));
    }

    /**
     * Same, with the reading that cycle recorded: the sticky branch anchors on it,
     * so a row seeded without one would only exercise the fallback comparison.
     */
    private void seedRecordedBlock(Instant windowEnd, Instant blockedAt, BigDecimal observedUsed) {
        jdbc.update("""
                INSERT INTO quota_enforcement (rule_id, tenant_id, scope_type, scope_id, metric, period, window_end,
                    blocked_at, observed_used)
                VALUES (:rule, :tenantId, 'PROJECT', :project, 'TOKENS', 'DAILY', :windowEnd, :blockedAt, :observedUsed)
                """,
                new MapSqlParameterSource("rule", RULE).addValue("tenantId", TENANT).addValue("project", PROJECT)
                        .addValue("windowEnd", Timestamp.from(windowEnd))
                        .addValue("blockedAt", Timestamp.from(blockedAt)).addValue("observedUsed", observedUsed));
    }

    private long blockedRuleRows() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM quota_enforcement WHERE rule_id = :rule",
                new MapSqlParameterSource("rule", RULE), Long.class);
        return count == null ? 0L : count;
    }

    private long windowEventCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM usage_event WHERE tenant_id = :tenant",
                new MapSqlParameterSource("tenant", TENANT), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * The token reading {@code QuotaWatermarks} derives for the blocked project
     * today (UTC).
     */
    private long windowTokens() {
        Long tokens = jdbc.queryForObject(
                """
                        SELECT COALESCE(SUM(COALESCE(input_tokens, prompt_tokens) + COALESCE(output_tokens, completion_tokens)), 0)
                          FROM usage_event WHERE tenant_id = :tenant AND project_id = :project
                           AND occurred_at >= :from AND occurred_at < :to
                        """,
                new MapSqlParameterSource("tenant", TENANT).addValue("project", PROJECT)
                        .addValue("from",
                                Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()))
                        .addValue("to", Timestamp.from(
                                LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())),
                Long.class);
        return tokens == null ? 0L : tokens;
    }

    /**
     * The gateway-visible block row, as {@code JdbcRouteSnapshotLoader} reads it.
     */
    private List<String> blockRows() {
        return jdbc
                .queryForList("SELECT scope_type || ' ' || scope_id || ' until ' || window_end FROM quota_enforcement"
                        + " WHERE rule_id = :rule", new MapSqlParameterSource("rule", RULE), String.class);
    }

    private void seedAdmin() {
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                VALUES (:id, 'ph67-quota', 'PH67 Quota', 'ACTIVE', 0, now(), now())
                """, new MapSqlParameterSource("id", TENANT));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                    must_change_password, failed_login_count, version, created_at, updated_at)
                VALUES (:id, :tenantId, 'ph67-quota-admin', 'PH67 Admin', 'x', 'SYSTEM_ADMIN', 'ACTIVE',
                    false, 0, 0, now(), now())
                """, new MapSqlParameterSource("id", ADMIN).addValue("tenantId", TENANT));
    }

    /**
     * The project must be a real {@code projects} row: the watermark aggregation
     * groups by {@code "project"}, and that group spec INNER JOINs {@code projects}
     * on {@code p.id = ue.project_id}. A dangling project id would drop every usage
     * row.
     */
    private void seedProject() {
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version, created_at, updated_at)
                VALUES (:id, :tenantId, 'PH67-P1', 'PH67 Project', 'ACTIVE', 'ph67', 0, now(), now())
                """, new MapSqlParameterSource("id", PROJECT).addValue("tenantId", TENANT));
    }

    /** A REJECT rule on the seeded project, daily window, TOKENS metric. */
    private void seedRejectRule() {
        jdbc.update("""
                INSERT INTO quota_rules (id, tenant_id, scope_type, scope_id, metric, period, limit_value,
                    warn_percent, status, created_by, version, created_at, updated_at, action)
                VALUES (:id, :tenantId, 'PROJECT', :scopeId, 'TOKENS', 'DAILY', :limit, 80, 'ACTIVE', :createdBy,
                    0, now(), now(), 'REJECT')
                """, new MapSqlParameterSource("id", RULE).addValue("tenantId", TENANT).addValue("scopeId", PROJECT)
                .addValue("limit", LIMIT_TOKENS).addValue("createdBy", ADMIN));
    }

    private void seedUsage() {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, provider_request_id, virtual_key_id, project_id,
                    provider_product_id, model_id, cache_level, input_tokens, output_tokens, total_tokens,
                    latency_ms, upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                SELECT md5('ph67-quota-' || i)::uuid, :tenantId, 'ph67-quota-' || i,
                       '1e000000-0000-4000-8000-0000000000f1', :projectId,
                       '1e000000-0000-4000-8000-0000000000f3', 'ph67-model', 'UPSTREAM', 300, 100, 400,
                       42, 200, TRUE, FALSE, 'ph67-quota-gw-' || i, now() - interval '1 minute'
                  FROM generate_series(1, :count) i
                """, new MapSqlParameterSource("tenantId", TENANT).addValue("projectId", PROJECT).addValue("count",
                WINDOW_EVENTS));
    }

    private void reset() {
        MapSqlParameterSource tenant = new MapSqlParameterSource("tenantId", TENANT);
        for (String table : List.of("usage_adjustments", "quota_enforcement", "quota_rules", "usage_deletions",
                "admin_audit_events", "usage_event", "users", "projects")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = :tenantId", tenant);
        }
        jdbc.update("DELETE FROM tenants WHERE id = :tenantId", tenant);
    }
}
