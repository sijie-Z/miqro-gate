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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 粘性判定的分支矩阵（#1316）：{@code QuotaEnforcementService} 只在两种文档写明的路径上解除
 * block——窗口滚过去（{@code window_end} 已过）或管理员改动规则（{@code updated_at} 晚于判定
 * 记录时刻）——其余情况（水位掉到限额以下、水位算不出来）都必须保留上一轮判定。
 *
 * <p>
 * 集成测试（{@code QuotaBlockSurvivesUsageDeletionIntegrationTest}）在真库上覆盖"删数据"与
 * "跨窗口 / 提额"两条端到端路径；这里补它造不出来的那一格：水位计算抛异常。断言方式是"没有发生
 * 重写"——判定集不变时服务直接返回，不 DELETE、不广播，网关快照因此保持原样。
 * </p>
 */
class QuotaEnforcementServiceStickyVerdictTest {

    private static final UUID TENANT = UUID.fromString("1e000000-0000-4000-8000-00000000c067");
    private static final UUID ADMIN = UUID.fromString("1e000000-0000-4000-8000-00000000a067");
    private static final UUID RULE = UUID.fromString("1e000000-0000-4000-8000-00000000c068");
    private static final UUID OTHER_RULE = UUID.fromString("1e000000-0000-4000-8000-00000000c069");
    private static final UUID PROJECT = UUID.fromString("1e000000-0000-4000-8000-0000000000f2");

    /** The window the verdict was recorded in still has hours to go. */
    private static final Instant WINDOW_END = Instant.now().plusSeconds(6 * 3_600);
    /** The moment the verdict was recorded, an hour ago. */
    private static final Instant BLOCKED_AT = Instant.now().minusSeconds(3_600);
    /** The rule was last touched two hours ago, i.e. before the verdict. */
    private static final Instant RULE_UNTOUCHED_SINCE = Instant.now().minusSeconds(7_200);

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
        givenBlockedRuleIds(RULE);
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
    @DisplayName("管理员改动规则之后判定消失：提高限额/停用规则仍是恢复路径")
    void ruleEditedAfterTheVerdictDropsIt() {
        Mockito.when(rules.findAllActiveReject()).thenReturn(List.of(rule(Instant.now())));
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());

        service.evaluate();

        assertDropped();
    }

    @Test
    @DisplayName("结转的判定写回它自己的 window_end 与判定时刻：窗口不会漂移、管理员改动的比较基准不变")
    void carriedVerdictKeepsItsOriginalWindowEndAndDecisionTime() {
        Mockito.when(watermarks.evaluate(Mockito.any(), Mockito.any())).thenReturn(reading());
        // Another rule's verdict changed, so the table does get rewritten this cycle.
        givenBlockedRuleIds(RULE, OTHER_RULE);

        service.evaluate();

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        Mockito.verify(jdbc).update(Mockito.contains("INSERT INTO quota_enforcement"), params.capture());
        assertThat(params.getValue().getValue("windowEnd")).isEqualTo(Timestamp.from(WINDOW_END));
        assertThat(params.getValue().getValue("blockedAt")).isEqualTo(Timestamp.from(BLOCKED_AT));
        Mockito.verify(publisher).publishChanged();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The blocked rule as the repository hands it over. */
    private QuotaRule rule(Instant updatedAt) {
        return new QuotaRule(RULE, TENANT, QuotaScopeType.PROJECT, PROJECT, QuotaMetric.TOKENS, QuotaPeriod.DAILY,
                QuotaAction.REJECT, 1_000L, 80, QuotaRuleStatus.ACTIVE, ADMIN, 1,
                Instant.now().minusSeconds(86_400), updatedAt);
    }

    /** A live watermark reading that is not EXCEEDED. */
    private QuotaWatermarks.Watermark reading() {
        return new QuotaWatermarks.Watermark(BigDecimal.valueOf(10), BigDecimal.valueOf(1), "NORMAL", null, null,
                Instant.now().minusSeconds(3_600), WINDOW_END);
    }

    /**
     * The verdict row the service maps out of {@code quota_enforcement}. {@code doAnswer}
     * rather than {@code when(...).thenAnswer(...)}: a later test re-records the table, and
     * plain stubbing would run the previous answer during the stubbing call itself (with the
     * argument matchers' nulls) before the new row ever lands.
     */
    private void givenRecorded(UUID ruleId, Instant windowEnd, Instant blockedAt) throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getObject("rule_id")).thenReturn(ruleId);
        Mockito.when(rs.getTimestamp("window_end")).thenReturn(Timestamp.from(windowEnd));
        Mockito.when(rs.getTimestamp("blocked_at")).thenReturn(Timestamp.from(blockedAt));
        Mockito.doAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(Mockito.anyString(), Mockito.anyMap(), Mockito.<RowMapper<Object>>any());
    }

    /** The rule ids the previous cycle left behind. */
    private void givenBlockedRuleIds(UUID... ruleIds) {
        Mockito.when(jdbc.queryForList("SELECT rule_id FROM quota_enforcement", Map.of(), UUID.class))
                .thenReturn(List.of(ruleIds));
    }

    /**
     * The verdict set came out identical to the recorded one, so the service returns
     * before rewriting anything: no DELETE, no INSERT, no route refresh — the gateway
     * keeps serving the block.
     */
    private void assertVerdictSetUntouched() {
        Mockito.verify(jdbc, Mockito.never()).update(Mockito.startsWith("DELETE"), Mockito.anyMap());
        Mockito.verify(jdbc, Mockito.never()).update(Mockito.contains("INSERT INTO quota_enforcement"),
                Mockito.any(MapSqlParameterSource.class));
        Mockito.verifyNoInteractions(publisher);
    }

    /** The recorded verdict was cleared: the table is rewritten empty and a refresh is published. */
    private void assertDropped() {
        Mockito.verify(jdbc).update("DELETE FROM quota_enforcement", Map.of());
        Mockito.verify(publisher).publishChanged();
    }
}
