package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.QuotaAction;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 粘性判定的分支矩阵（#1316）：{@code QuotaEnforcementService} 只在文档写明的恢复路径上解除
 * block——窗口滚过去（{@code window_end} 已过）、管理员把限额提到记录在案的读数之上、规则被停用
 * 或删除——其余情况（水位掉到限额以下、水位算不出来）都必须保留上一轮判定。
 *
 * <p>
 * 判定的仲裁者是<b>表里那条读数</b>，不是"管理员有没有动过这条规则"：管理员保存规则必然把 {@code updated_at}
 * 顶到当下，而判定集每 60 秒才重算一次，于是"保存 → 删用量 → 下一轮评估"这个
 * 顺序里，保存先给恢复路径上膛、删用量再扣扳机——规则其实仍然超限，block 却被解掉。读数比较没有 这个时间窗：记录读数高于（改后的）限额就保持
 * block，低于才解除。
 * </p>
 *
 * <p>
 * 集成测试（{@code QuotaBlockSurvivesUsageDeletionIntegrationTest}）在真库上覆盖"删数据"与 "跨窗口
 * / 提额"两条端到端路径；这里补它造不出来的几格：水位计算抛异常、记录读数在两次推导之间 上移（不能落成过期下界）、以及 V76
 * 之前的旧行（没有读数）退回按改动时刻比较。断言方式是"没有 发生重写"——(规则, 窗口, 判定时刻) 三元组不变时服务不
 * DELETE、不广播，网关快照因此保持原样。
 * </p>
 */
class QuotaEnforcementServiceStickyVerdictTest {

    private static final UUID TENANT = UUID.fromString("1e000000-0000-4000-8000-00000000c067");
    private static final UUID ADMIN = UUID.fromString("1e000000-0000-4000-8000-00000000a067");
    private static final UUID RULE = UUID.fromString("1e000000-0000-4000-8000-00000000c068");
    private static final UUID OTHER_RULE = UUID.fromString("1e000000-0000-4000-8000-00000000c069");
    private static final UUID PROJECT = UUID.fromString("1e000000-0000-4000-8000-0000000000f2");

    /** The limit the recorded verdict was reached under. */
    private static final long LIMIT_TOKENS = 1_000L;
    /** The usage the last still-exceeded cycle saw, i.e. what the table holds. */
    private static final BigDecimal RECORDED_READING = BigDecimal.valueOf(2_000L);

    /** The window the verdict was recorded in still has hours to go. */
    private static final Instant WINDOW_END = Instant.now().plusSeconds(6 * 3_600);
    /** The moment the verdict was recorded, an hour ago. */
    private static final Instant BLOCKED_AT = Instant.now().minusSeconds(3_600);
    /** The rule was last touched two hours ago, i.e. before the verdict. */
    private static final Instant RULE_UNTOUCHED_SINCE = Instant.now().minusSeconds(7_200);
    /** The admin saved the rule just now, i.e. after the verdict was recorded. */
    private static final Instant RULE_EDITED_AT = Instant.now();

    private QuotaRuleRepository rules;
    private QuotaWatermarks watermarks;
    private NamedParameterJdbcTemplate jdbc;
    private RouteRefreshPublisher publisher;
    private QuotaEnforcementService service;

    @BeforeEach
    void setUp() throws Exception {
        rules = Mockito.mock(QuotaRuleRepository.class);
        watermarks = Mockito.mock(QuotaWatermarks.class);
        jdbc = Mockito.mock(NamedParameterJdbcTemplate.class);
        publisher = Mockito.mock(RouteRefreshPublisher.class);
        service = new QuotaEnforcementService(rules, watermarks, jdbc, publisher);

        Mockito.when(rules.findAllActiveReject()).thenReturn(List.of(rule(RULE_UNTOUCHED_SINCE)));
        givenRecorded(RULE, WINDOW_END, BLOCKED_AT);
    }

    @Test
    @DisplayName("水位计算失败时保留上一轮判定，不静默放行（ADR-0020 §4 / #1316）")
    void failingWatermarkKeepsTheRecordedVerdict() {
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("usage aggregation unavailable"));

        service.evaluate();

        assertVerdictSetUntouched();
    }

    @Test
    @DisplayName("实时水位掉到限额以下（窗口内用量行被删）时保留判定（#1316）")
    void liveReadingBelowTheLimitKeepsTheRecordedVerdict() {
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());

        service.evaluate();

        assertVerdictSetUntouched();
    }

    @Test
    @DisplayName("窗口滚过去之后判定消失：粘性判定不得永久拦住流量")
    void expiredWindowDropsTheRecordedVerdict() throws Exception {
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());
        givenRecorded(RULE, Instant.now().minusSeconds(3_600), Instant.now().minusSeconds(7_200));

        service.evaluate();

        assertDropped();
    }

    @Test
    @DisplayName("管理员把限额提到记录读数之上：判定消失（提高限额仍是恢复路径）")
    void adminRaiseAboveTheRecordedReadingDropsTheBlock() {
        Mockito.when(rules.findAllActiveReject()).thenReturn(List.of(rule(Instant.now(), 5_000L)));
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());

        service.evaluate();

        assertDropped();
    }

    @Test
    @DisplayName("管理员把限额提到恰好等于记录读数：仍然拦（`达到限额即 EXCEEDED`，不是严格大于）")
    void adminRaiseToExactlyTheRecordedReadingStillBlocks() {
        // The equality boundary ADR-0020 §4 pins the two sides to: the trigger calls a
        // scope EXCEEDED at used >= limit, so a release test that asked for limit >
        // reading would let a scope through that the evaluator would block again on its
        // very next live cycle. Raising 1000 -> 2000 against a recorded 2000 is
        // therefore not a recovery; one more token is.
        Mockito.when(rules.findAllActiveReject()).thenReturn(List.of(rule(RULE_EDITED_AT, 2_000L)));
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());

        service.evaluate();

        assertVerdictSetUntouched();
    }

    @Test
    @DisplayName("规则被停用/删除（不再出现在 ACTIVE REJECT 里）：判定消失")
    void ruleNoLongerActiveDropsTheRecordedVerdict() {
        Mockito.when(rules.findAllActiveReject()).thenReturn(List.of());
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());

        service.evaluate();

        assertDropped();
    }

    @Test
    @DisplayName("结转的判定写回它自己的 window_end 与判定时刻：窗口不会漂移、管理员改动的比较基准不变")
    void carriedVerdictKeepsItsOriginalWindowEndAndDecisionTime() throws Exception {
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());
        // A row the new cycle no longer produces, so the table does get rewritten this
        // cycle.
        givenRecorded(new RecordedRow(RULE, WINDOW_END, BLOCKED_AT, "TOKENS", "DAILY", RECORDED_READING),
                new RecordedRow(OTHER_RULE, Instant.now().plusSeconds(7_200), BLOCKED_AT, "TOKENS", "DAILY",
                        RECORDED_READING));

        service.evaluate();

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        Mockito.verify(jdbc).update(Mockito.contains("INSERT INTO quota_enforcement"), params.capture());
        assertThat(params.getValue().getValue("windowEnd")).isEqualTo(Timestamp.from(WINDOW_END));
        assertThat(params.getValue().getValue("blockedAt")).isEqualTo(Timestamp.from(BLOCKED_AT));
        Mockito.verify(publisher).publishChanged();
    }

    @Test
    @DisplayName("跨窗口仍超限时，记录的 window_end 前进到当前窗口，判定时刻保持为这段 block 的起点（#1316）")
    void liveReadingStillExceededAdvancesTheRecordedWindow() throws Exception {
        // The recorded verdict belongs to the window that just ended.
        givenRecorded(RULE, Instant.now().minusSeconds(600), BLOCKED_AT);
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(exceededReading());

        service.evaluate();

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        Mockito.verify(jdbc).update("DELETE FROM quota_enforcement", Map.of());
        Mockito.verify(jdbc).update(Mockito.contains("INSERT INTO quota_enforcement"), params.capture());
        assertThat(params.getValue().getValue("windowEnd")).as("判定必须在当前窗口里继续有效").isEqualTo(Timestamp.from(WINDOW_END));
        assertThat(params.getValue().getValue("blockedAt")).as("同一段连续 block 的判定时刻不能被刷新，否则管理员改动的比较基准会跟着漂")
                .isEqualTo(Timestamp.from(BLOCKED_AT));
        Mockito.verify(publisher).publishChanged();
    }

    @Test
    @DisplayName("管理员保存了规则但限额仍低于记录读数、随后水位下跌：判定必须保留（#1316）")
    void adminSaveBelowTheRecordedReadingDoesNotArmARecovery() {
        // The admin's save lands *after* the last still-exceeded derivation (updated_at
        // =
        // RULE_EDITED_AT, well past the recorded decision time) and the next derivation
        // happens only after the retention deletion has zeroed the live reading. That
        // save
        // is not a recovery — the limit it wrote (1500) is still under the 2000 tokens
        // the
        // last reading saw — so the recorded verdict has to hold. Deciding this from
        // updated_at would lift the block here even though nothing recovered.
        Mockito.when(rules.findAllActiveReject()).thenReturn(List.of(rule(RULE_EDITED_AT, 1_500L)));
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());

        service.evaluate();

        assertVerdictSetUntouched();
    }

    @Test
    @DisplayName("规则仍然超限时，记录读数每一轮都要跟上：过期下界会在提额时错判成'已恢复'（#1316）")
    void stillExceededCyclesRefreshTheRecordedReading() throws Exception {
        // Verdict unchanged (same rule, same window, same decision time) but the live
        // reading has moved 2000 -> 3000. The stored reading must move too: left at
        // 2000 it
        // becomes a stale lower bound, and a later admin raise to 2500 — which does not
        // recover the scope — would compare as "2500 >= 2000, recovered" and lift the
        // block.
        givenRecorded(RULE, WINDOW_END, BLOCKED_AT);
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(exceededReading(3_000L));

        service.evaluate();

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        Mockito.verify(jdbc).update(Mockito.contains("UPDATE quota_enforcement"), params.capture());
        assertThat(params.getValue().getValue("observedUsed")).as("表里的读数必须是这一轮看到的 3000，不能停在 2000")
                .isEqualTo(BigDecimal.valueOf(3_000L));
        assertThat(params.getValue().getValue("ruleId")).isEqualTo(RULE);
        // Nothing the gateway can see changed, so no rewrite and no route refresh: the
        // stored reading is bookkeeping for the control plane, not a snapshot fact.
        assertVerdictSetUntouched();
    }

    @Test
    @DisplayName("V76 之前写下的行没有读数：退回按改动时刻比较，未改动的规则判定必须保留")
    void legacyRowWithoutAReadingFallsBackToTheEditComparison() throws Exception {
        givenRecorded(new RecordedRow(RULE, WINDOW_END, BLOCKED_AT, "TOKENS", "DAILY", null));
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());

        service.evaluate();

        assertVerdictSetUntouched();
    }

    @Test
    @DisplayName("水位计算失败时，管理员刚改动过规则也必须保留判定：故障路径不得放行（ADR-0020 §4）")
    void failingWatermarkKeepsTheVerdictEvenAfterTheRuleWasEdited() {
        Mockito.when(rules.findAllActiveReject()).thenReturn(List.of(rule(Instant.now())));
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("usage aggregation unavailable"));

        service.evaluate();

        assertVerdictSetUntouched();
    }

    @Test
    @DisplayName("没有历史判定行的超限规则按当前时刻新建 block")
    void liveReadingExceededWithoutARecordedRowStartsANewBlock() throws Exception {
        givenNoRecordedRow();
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(exceededReading());

        service.evaluate();

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        Mockito.verify(jdbc).update(Mockito.contains("INSERT INTO quota_enforcement"), params.capture());
        assertThat(params.getValue().getValue("windowEnd")).isEqualTo(Timestamp.from(WINDOW_END));
        assertThat(((Timestamp) params.getValue().getValue("blockedAt")).toInstant())
                .isBetween(Instant.now().minusSeconds(60), Instant.now());
        Mockito.verify(publisher).publishChanged();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The blocked rule as the repository hands it over: it still carries the limit
     * it was blocked under.
     */
    private QuotaRule rule(Instant updatedAt) {
        return rule(updatedAt, LIMIT_TOKENS);
    }

    /** Same, with an admin-raised limit. */
    private QuotaRule rule(Instant updatedAt, long limitValue) {
        return new QuotaRule(RULE, TENANT, QuotaScopeType.PROJECT, PROJECT, QuotaMetric.TOKENS, QuotaPeriod.DAILY,
                QuotaAction.REJECT, limitValue, 80, QuotaRuleStatus.ACTIVE, ADMIN, 1,
                Instant.now().minusSeconds(86_400), updatedAt);
    }

    /** A live watermark reading that is not EXCEEDED. */
    private QuotaWatermarks.Watermark reading() {
        return new QuotaWatermarks.Watermark(BigDecimal.valueOf(10), BigDecimal.valueOf(1), "NORMAL", null, null,
                Instant.now().minusSeconds(3_600), WINDOW_END);
    }

    /** A live watermark reading that is still EXCEEDED in the current window. */
    private QuotaWatermarks.Watermark exceededReading() {
        return exceededReading(2_000L);
    }

    /** Same, with the observed usage the rule is over its limit by. */
    private QuotaWatermarks.Watermark exceededReading(long used) {
        return new QuotaWatermarks.Watermark(BigDecimal.valueOf(used), BigDecimal.valueOf(used), "EXCEEDED", null, null,
                Instant.now().minusSeconds(600), WINDOW_END);
    }

    /** The table is empty: no verdict was carried over from an earlier window. */
    private void givenNoRecordedRow() throws Exception {
        Mockito.doAnswer(invocation -> List.of()).when(jdbc).query(Mockito.anyString(), Mockito.anyMap(),
                Mockito.<RowMapper<Object>>any());
    }

    /**
     * One verdict row the service maps out of {@code quota_enforcement}.
     * {@code doAnswer} rather than {@code when(...).thenAnswer(...)}: a later test
     * re-records the table, and plain stubbing would run the previous answer during
     * the stubbing call itself (with the argument matchers' nulls) before the new
     * row ever lands.
     */
    private void givenRecorded(UUID ruleId, Instant windowEnd, Instant blockedAt) throws Exception {
        givenRecorded(new RecordedRow(ruleId, windowEnd, blockedAt, "TOKENS", "DAILY", RECORDED_READING));
    }

    /**
     * The verdict rows the previous cycle left behind; each needs its own
     * {@code ResultSet}.
     */
    private void givenRecorded(RecordedRow... rows) throws Exception {
        Mockito.doAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(2);
            List<Object> mapped = new ArrayList<>(rows.length);
            for (int i = 0; i < rows.length; i++) {
                ResultSet rs = Mockito.mock(ResultSet.class);
                Mockito.when(rs.getObject("rule_id")).thenReturn(rows[i].ruleId());
                Mockito.when(rs.getTimestamp("window_end")).thenReturn(Timestamp.from(rows[i].windowEnd()));
                Mockito.when(rs.getTimestamp("blocked_at")).thenReturn(Timestamp.from(rows[i].blockedAt()));
                Mockito.when(rs.getString("metric")).thenReturn(rows[i].metric());
                Mockito.when(rs.getString("period")).thenReturn(rows[i].period());
                Mockito.when(rs.getBigDecimal("observed_used")).thenReturn(rows[i].observedUsed());
                mapped.add(mapper.mapRow(rs, i));
            }
            return mapped;
        }).when(jdbc).query(Mockito.anyString(), Mockito.anyMap(), Mockito.<RowMapper<Object>>any());
    }

    /**
     * A row as {@code quota_enforcement} would hold it; a null reading predates
     * V76.
     */
    private record RecordedRow(UUID ruleId, Instant windowEnd, Instant blockedAt, String metric, String period,
            BigDecimal observedUsed) {
    }

    /**
     * The verdict set came out identical to the recorded one, so the service
     * returns before rewriting anything: no DELETE, no INSERT, no route refresh —
     * the gateway keeps serving the block.
     */
    private void assertVerdictSetUntouched() {
        Mockito.verify(jdbc, Mockito.never()).update(Mockito.startsWith("DELETE"), Mockito.anyMap());
        Mockito.verify(jdbc, Mockito.never()).update(Mockito.contains("INSERT INTO quota_enforcement"),
                Mockito.any(MapSqlParameterSource.class));
        Mockito.verifyNoInteractions(publisher);
    }

    /**
     * The recorded verdict was cleared: the table is rewritten empty and a refresh
     * is published.
     */
    private void assertDropped() {
        Mockito.verify(jdbc).update("DELETE FROM quota_enforcement", Map.of());
        Mockito.verify(publisher).publishChanged();
    }
}
