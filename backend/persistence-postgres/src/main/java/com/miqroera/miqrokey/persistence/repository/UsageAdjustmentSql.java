package com.miqroera.miqrokey.persistence.repository;

/**
 * The one definition of how a usage adjustment (#709) is applied in SQL.
 *
 * <p>
 * Every read path that reports adjusted usage — the admin/self-service detail
 * list, the aggregates behind the summary and billing, and the usage export —
 * must net the same way. Duplicating these fragments per repository would let
 * them drift apart, which is precisely the failure the net-in-SQL approach was
 * chosen to avoid: two surfaces quietly disagreeing about the same number.
 * </p>
 *
 * <p>
 * Public because the usage export builds its own query over {@code usage_event}
 * and must net identically; sharing these fragments is the lesser evil next to
 * a second hand-maintained copy that could drift. (That boundary is already
 * crossed — {@code ExportTaskService} writes its own SQL against the table.)
 * </p>
 *
 * <p>
 * Assumes the query aliases {@code usage_event} as {@code ue} and has joined
 * {@link #ADJUSTMENT_TOTALS}, which introduces the alias {@code adj}.
 * </p>
 */
public final class UsageAdjustmentSql {

    private UsageAdjustmentSql() {
    }

    /**
     * Per-event adjustment totals, pre-aggregated into a derived table.
     *
     * <p>
     * Deliberately <b>not</b> a direct {@code JOIN usage_adjustments}: one event
     * can carry many adjustments, so a plain join multiplies the
     * {@code usage_event} row and inflates every {@code SUM} in whichever query
     * carries it. Grouping by {@code usage_event_id} keeps one row per event.
     * </p>
     *
     * <p>
     * Deliberately <b>not</b> a correlated {@code LATERAL} either, which is what
     * this used to be. Correlating on {@code ue} forces the planner into a Nested
     * Loop with one inner subplan per outer row, and the per-row estimate
     * multiplied out lands the plan cost at 1.55M–1.85M — past
     * {@code jit_inline_above_cost} and {@code jit_optimize_above_cost} (both
     * default 500000). PostgreSQL then runs the full LLVM optimization and inlining
     * passes on <b>every</b> execution, costing 1.4–5.8 s of compile time per
     * query. Uncorrelated, the same SQL plans at 132k–189k and only the cheap
     * codegen runs (#1322).
     * </p>
     *
     * <p>
     * The uncorrelated shape is a trade, not a free win: it cannot see the outer
     * query's filter or {@code LIMIT}, so it folds the whole ledger even when the
     * caller asked for one page, where the lazy correlated form evaluated only the
     * handful of surviving rows. Measured on 380,000 usage events and 4,018
     * adjustments, the report aggregates and the deep-page list gain 2.2x-3.7x (for
     * example 3.3 s to 1.2 s), while the already-fast shallow paths pay the whole
     * aggregate: {@code find_records} 2 ms to 38 ms, {@code agg_usage_PROJECT_FULL}
     * 1 ms to 14 ms, {@code find_records_FULL} 1 ms to 13 ms. Every result is
     * unchanged, and the correlated alternative costs 1.5 s more than it saves, so
     * the trade is kept — but the shallow-page cost is real, not hidden.
     * </p>
     *
     * <p>
     * Grouping and joining on <b>both</b> columns is what keeps this equivalent to
     * the correlated form: {@code usage_adjustments.usage_event_id} is a
     * single-column FK to a globally unique {@code usage_event.id}, so a
     * mismatched-tenant adjustment is still excluded, exactly as the correlation
     * excluded it. {@code idx_usage_adjustments_usage_event (tenant_id,
     * usage_event_id)} still serves the GROUP BY and the join; no new index.
     * </p>
     *
     * <p>
     * A grouped derived table emits <b>no</b> row for an event with no adjustments,
     * where the old ungrouped aggregate emitted one row of zeros. That difference
     * is invisible to all three call sites: each already {@code COALESCE}s every
     * {@code adj.*} it reads ({@link #ADJUSTED_FLAG}, {@link #netExpr},
     * {@code UsageStatsRepositoryImpl}'s adjusted-token sum). Note the leading
     * newline: callers concatenate this onto a join string that does not end with
     * one.
     * </p>
     */
    public static final String ADJUSTMENT_TOTALS = "\n" + """
            LEFT JOIN (
                SELECT a.usage_event_id, a.tenant_id,
                       COALESCE(SUM(a.input_tokens_delta), 0) AS input_delta,
                       COALESCE(SUM(a.output_tokens_delta), 0) AS output_delta,
                       COALESCE(SUM(a.cache_read_tokens_delta), 0) AS cache_read_delta,
                       COALESCE(SUM(a.cache_creation_tokens_delta), 0) AS cache_creation_delta,
                       COUNT(a.id) AS adjustment_count
                  FROM usage_adjustments a
                 GROUP BY a.usage_event_id, a.tenant_id
            ) adj ON adj.usage_event_id = ue.id AND adj.tenant_id = ue.tenant_id
            """;

    /**
     * True when the event carries <b>any</b> correction, including one that has
     * since been reversed.
     *
     * <p>
     * Judged by the number of rows, not by their sum (#774). Summing first made a
     * correction and its reversal cancel out and read as "never adjusted" — but
     * "corrected and put back" and "nobody ever touched this" are different facts,
     * and the append-only ledger exists precisely to keep the first one visible.
     * </p>
     *
     * <p>
     * This is also the only reading worth a server field: a client holding both
     * {@code *Tokens} and {@code net*Tokens} can already tell whether the numbers
     * differ, so a flag that only repeated that would carry nothing.
     * </p>
     */
    public static final String ADJUSTED_FLAG = """
            (COALESCE(adj.adjustment_count, 0) > 0)
            """;

    /**
     * net = normalized observed + delta.
     *
     * <p>
     * The CAST is required: {@code SUM(bigint)} yields {@code numeric} in
     * PostgreSQL, and the driver refuses to hand a numeric back as a {@code Long}.
     * That failure is easy to misread — it reaches the client as a 409
     * {@code RESOURCE_CONFLICT}, because the generic data-integrity handler catches
     * it, so it does not look like a query bug at all.
     * </p>
     *
     * <p>
     * Stays NULL when the row has neither an observed nor an adjusted value: "no
     * data" must not read as "zero tokens".
     * </p>
     */
    public static String netExpr(String observed, String delta) {
        return "CAST(CASE WHEN " + observed + " IS NULL AND COALESCE(" + delta + ", 0) = 0 THEN NULL ELSE COALESCE("
                + observed + ", 0) + COALESCE(" + delta + ", 0) END AS bigint)";
    }

    /**
     * The net expression for one token dimension of {@code ue}, defaulting to the
     * normalized observed column.
     */
    public static String netInput() {
        return netExpr("COALESCE(ue.input_tokens, ue.prompt_tokens)", "adj.input_delta");
    }

    public static String netOutput() {
        return netExpr("COALESCE(ue.output_tokens, ue.completion_tokens)", "adj.output_delta");
    }

    public static String netCacheRead() {
        return netExpr("ue.cache_read_input_tokens", "adj.cache_read_delta");
    }

    public static String netCacheCreation() {
        return netExpr("ue.cache_creation_input_tokens", "adj.cache_creation_delta");
    }
}
