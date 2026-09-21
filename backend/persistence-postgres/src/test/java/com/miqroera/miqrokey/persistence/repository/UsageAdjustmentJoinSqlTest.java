package com.miqroera.miqrokey.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.GroupBy;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.UsageFilter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The adjustment aggregate must stay uncorrelated to the row it decorates
 * (#1322).
 *
 * <p>
 * The join used to be a correlated {@code LEFT JOIN LATERAL}, re-deriving the
 * per-event totals for each {@code usage_event} row. Correlation forces a
 * Nested Loop with one inner subplan per outer row, and the multiplied-out
 * estimate lands the plan cost at 1.55M–1.85M — past
 * {@code jit_inline_above_cost} and {@code jit_optimize_above_cost} (both
 * default 500000). PostgreSQL then runs the full LLVM optimization and inlining
 * passes on every execution, which measured 1.4–5.8 s of compile time on top of
 * the scan. The uncorrelated derived table plans at 132k–189k and skips both
 * passes; on interleaved A/B medians every heavy statement improved by
 * 2.2x-3.7x (for example {@code agg_usage_USER} 3.3 s to 1.2 s).
 * </p>
 *
 * <p>
 * The uncorrelated shape also makes the shallow first-page list *slower*, since
 * it folds the whole ledger regardless of the outer {@code LIMIT} — 2 ms to 38
 * ms in the same run. These assertions pin the winning shape, not a claim that
 * nothing regressed; the full trade-off is on
 * {@link UsageAdjustmentSql#ADJUSTMENT_TOTALS}.
 * </p>
 *
 * <p>
 * The regression is invisible to every functional test: both shapes return the
 * same rows, so only the plan differs. The assertions below therefore pin the
 * <b>shape</b> rather than the result — the aggregate must not reference the
 * outer alias, must not be a {@code LATERAL}, and must group by both join
 * columns so one event with many adjustments still yields one row (the reason a
 * plain join is not an option either).
 * </p>
 */
@DisplayName("Summary SQL: the adjustment aggregate is uncorrelated, so the plan stays under the JIT pass thresholds (#1322)")
class UsageAdjustmentJoinSqlTest {

    /** Seed tenant from V1 — the filter needs one; no row is ever read. */
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-21T00:00:00Z");
    private static final UsageFilter FILTER = new UsageFilter(TENANT, null, FROM, TO);

    @Test
    @DisplayName("the aggregate is composed as a LATERAL-free derived table")
    void adjustmentAggregateIsNotLateral() {
        // Asserted on the whole statement rather than on adjustmentSubquery(): a
        // LATERAL can only ever be the aggregate's own, but scoping the check to
        // the slice would make it depend on the slice boundaries staying right.
        assertThat(adjustmentSql()).doesNotContain("LATERAL");
    }

    @Test
    @DisplayName("the aggregate never reaches back to the outer usage_event alias — the correlation that cost the JIT passes")
    void adjustmentAggregateDoesNotReferenceTheOuterRow() {
        // A single `ue.` inside the subquery re-correlates it: the planner cannot
        // evaluate the aggregate once and reuse it, which is the whole defect.
        assertThat(adjustmentSubquery(adjustmentSql())).doesNotContain("ue.");
    }

    @Test
    @DisplayName("it still groups by both join columns, so many adjustments per event stay one row")
    void adjustmentAggregateKeepsOneRowPerEvent() {
        assertThat(adjustmentSubquery(adjustmentSql())).contains("GROUP BY a.usage_event_id, a.tenant_id")
                // A plain (non-LATERAL) join to usage_adjustments would multiply the
                // usage_event row and inflate every SUM in the query; the grouping is
                // what prevents that.
                .contains("COUNT(a.id) AS adjustment_count");
    }

    @Test
    @DisplayName("the join predicate matches both columns, so a mismatched-tenant adjustment is still excluded")
    void joinPredicateCoversBothColumns() {
        assertThat(adjustmentSql()).contains("ON adj.usage_event_id = ue.id AND adj.tenant_id = ue.tenant_id");
    }

    @Test
    @DisplayName("the detail list carries the same shape — the deep-page query was the other 1.7M plan")
    void detailListUsesTheSameShape() {
        String sql = captureSql(repository -> repository.findRecords(FILTER, 0, 50));
        assertThat(sql).doesNotContain("LATERAL");
        assertThat(adjustmentSubquery(sql)).doesNotContain("ue.");
        assertThat(sql).contains("ON adj.usage_event_id = ue.id AND adj.tenant_id = ue.tenant_id");
    }

    @Test
    @DisplayName("the observed basis drops the aggregate entirely — it has no delta to apply")
    void observedBasisCarriesNoAdjustmentJoin() {
        // Control: the OBSERVED reports showed no JIT cost precisely because they
        // never joined the ledger, which is what isolates the join shape as the
        // cause rather than table size or filter width.
        assertThat(captureSql(repository -> repository.aggregateObservedUsage(GroupBy.PROJECT, FILTER)))
                .doesNotContain("usage_adjustments");
    }

    // ------------------------------------------------------------------

    /** The adjustment join as the summary report composes it (ADJUSTED basis). */
    private static String adjustmentSql() {
        return captureSql(repository -> repository.aggregateUsage(GroupBy.PROJECT, FILTER, 480));
    }

    /**
     * The adjustment derived table: from the {@code LEFT JOIN} that introduces it
     * through the {@code ) adj ON} that closes it.
     *
     * <p>
     * Both ends matter. Starting at the {@code LEFT JOIN} rather than at the ledger
     * scan keeps the {@code LATERAL} keyword and the select list (where
     * {@code COUNT(...)} lives) inside the slice; stopping before the {@code ON}
     * clause keeps out the outer {@code ue.} references the join predicate is
     * entitled to make. Everything the subquery reads is in between, so an outer
     * alias reference cannot hide outside this slice.
     * </p>
     */
    private static String adjustmentSubquery(String sql) {
        int scan = sql.indexOf("FROM usage_adjustments a");
        assertThat(scan).as("the adjustment ledger scan in the composed SQL").isNotNegative();
        int start = sql.lastIndexOf("LEFT JOIN", scan);
        assertThat(start).as("the join introducing the adjustment aggregate").isNotNegative();
        int end = sql.indexOf(") adj ON ", scan);
        assertThat(end).as("the closing of the adjustment derived table").isNotNegative();
        return sql.substring(start, end);
    }

    /**
     * Runs the real repository against a mocked template and returns the SQL it
     * issued. The mock never invokes a callback, so no row is parsed and no
     * database is needed.
     */
    private static String captureSql(java.util.function.Consumer<UsageStatsRepositoryImpl> call) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        call.accept(new UsageStatsRepositoryImpl(jdbc, new ObjectMapper()));
        String sql = captured.get();
        assertThat(sql).as("composed SQL was issued").isNotNull();
        return sql;
    }
}
