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
 * {@link #ADJUSTMENT_LATERAL}, which introduces the alias {@code adj}.
 * </p>
 */
public final class UsageAdjustmentSql {

    private UsageAdjustmentSql() {
    }

    /**
     * Per-event adjustment totals, as a correlated LATERAL.
     *
     * <p>
     * Deliberately <b>not</b> a direct {@code JOIN usage_adjustments}: one event
     * can carry many adjustments, so a plain join multiplies the
     * {@code usage_event} row and inflates every {@code SUM} in whichever query
     * carries it. The correlation also rides
     * {@code idx_usage_adjustments_usage_event (tenant_id, usage_event_id)}.
     * </p>
     *
     * <p>
     * An aggregate without GROUP BY returns exactly one row, so an event with no
     * adjustments yields zeros instead of dropping out of the join. Note the
     * leading newline: callers concatenate this onto a join string that does not
     * end with one.
     * </p>
     */
    public static final String ADJUSTMENT_LATERAL = "\n" + """
            LEFT JOIN LATERAL (
                SELECT COALESCE(SUM(a.input_tokens_delta), 0) AS input_delta,
                       COALESCE(SUM(a.output_tokens_delta), 0) AS output_delta,
                       COALESCE(SUM(a.cache_read_tokens_delta), 0) AS cache_read_delta,
                       COALESCE(SUM(a.cache_creation_tokens_delta), 0) AS cache_creation_delta
                  FROM usage_adjustments a
                 WHERE a.tenant_id = ue.tenant_id AND a.usage_event_id = ue.id
            ) adj ON TRUE
            """;

    /** True when the event carries any non-zero correction. */
    public static final String ADJUSTED_FLAG = """
            (COALESCE(adj.input_delta, 0) <> 0 OR COALESCE(adj.output_delta, 0) <> 0
             OR COALESCE(adj.cache_read_delta, 0) <> 0 OR COALESCE(adj.cache_creation_delta, 0) <> 0)
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
