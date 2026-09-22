package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.usage.AdjustedUsageRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side access to tiered usage statistics ({@code usage_event} and
 * {@code cache_hit_event}).
 *
 * <p>
 * The repository returns pre-aggregated rows; cost math stays in the pure
 * {@link UsageStatsAggregator}. Both {@link #aggregateUsage} and
 * {@link #aggregateHits} share the same {@link GroupBy} dimension so their
 * results can be merged into one summary.
 * </p>
 *
 * <p>
 * Filters always carry {@code tenantId} and the caller-scoped key set; there is
 * deliberately no tenant-less query shape.
 * </p>
 */
public interface UsageStatsRepository {

    /** Aggregation dimension for usage statistics. */
    enum GroupBy {
        PROJECT, VIRTUAL_KEY, CACHE_LEVEL,
        /**
         * Calendar day (label = {@code YYYY-MM-DD}) in the caller's offset (#1050):
         * {@link #aggregateUsage(GroupBy, UsageFilter, int)} takes the fixed offset
         * from UTC and moves the bucket with it, while the database session's
         * {@code TimeZone} never moves it. Offset 0 is the UTC day.
         */
        DAY,
        /** I15 (doc 134892): per-consumer dimension (label = username). */
        USER,
        /** I15: per-model dimension (label = model id). */
        MODEL,
        /**
         * I15: calendar-month granularity (label = {@code YYYY-MM}), same offset rule
         * as {@link #DAY} (#1050).
         */
        MONTH,
        /**
         * Per-team dimension (label = team name, joined through the member's virtual
         * keys). A user in several teams is counted in each team's total — team totals
         * are attribution views, not a partition.
         */
        TEAM,
        /**
         * Per-provider-product dimension (#758, label = product display name) — the
         * "供应商统计" view: one row per product instance the tenant actually routed to.
         */
        PRODUCT
    }

    /**
     * Cross-tab dimension of the hourly report (#634). Every hourly row always
     * carries the project; the dimension adds a second grouping key: {@code NONE}
     * (hour × project), {@code USER} (hour × project × user), or {@code TEAM} (hour
     * × project × team — the same multi-team attribution view as
     * {@link GroupBy#TEAM}).
     */
    enum HourlyDimension {
        NONE, USER, TEAM
    }

    /**
     * One aggregated hour bucket of the hourly report (#634): the row counts usage
     * events started in {@code [hourStart, hourStart + 1h)} for one project (and,
     * when a cross-tab dimension is requested, one user or team). Tokens follow the
     * {@code UsageAggRow} conventions (input falls back to {@code prompt_tokens},
     * output to {@code completion_tokens}; absent values sum to 0).
     * {@code hourStart} is an instant aligned to the requested
     * {@code tzOffsetMinutes} hour boundary.
     */
    record HourlyUsageRow(Instant hourStart, UUID projectId, String projectLabel, UUID dimensionId,
            String dimensionLabel, long requests, long inputTokens, long outputTokens, long cacheReadTokens,
            long cacheCreationTokens) {

        /** Same total convention as {@code UsageStatsAggregator.Tokens#total()}. */
        public long totalTokens() {
            return inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens;
        }
    }

    /**
     * Scope of an aggregation or record listing. {@code virtualKeyIds} must be the
     * caller's own key set (enforced by the service layer) and {@code null} for
     * admin-scoped queries. All other dimensions are optional filters (G4.1):
     * {@code userId} / {@code projectId} / {@code credentialId} /
     * {@code subscriptionId} / {@code providerProductId} / {@code modelId}. The
     * filter always carries {@code tenantId}; there is deliberately no tenant-less
     * query shape.
     */
    record UsageFilter(UUID tenantId, Set<UUID> virtualKeyIds, UUID userId, UUID projectId, UUID credentialId,
            UUID subscriptionId, UUID providerProductId, String modelId, String clientIp, UUID teamId, Instant from,
            Instant to) {

        /** Self-service shape: caller-scoped key set, no extra dimensions. */
        public UsageFilter(UUID tenantId, Set<UUID> virtualKeyIds, Instant from, Instant to) {
            this(tenantId, virtualKeyIds, null, null, null, null, null, null, null, null, from, to);
        }
    }

    /**
     * Aggregated usage-event rows for the filter, one row per (group, product,
     * model, cache level) — exactly the input shape of
     * {@link UsageStatsAggregator#aggregate}. {@code day}/{@code month} buckets are
     * UTC; {@link #aggregateUsage(GroupBy, UsageFilter, int)} is the same query in
     * the caller's local day/month (#1050).
     */
    default List<UsageStatsAggregator.UsageAggRow> aggregateUsage(GroupBy groupBy, UsageFilter filter) {
        return aggregateUsage(groupBy, filter, 0);
    }

    /**
     * The same aggregation, with {@code day}/{@code month} buckets aligned to
     * {@code tzOffsetMinutes} from UTC (#1050): the caller's fixed offset, the same
     * shape the hourly report takes (and, like it, the only thing that moves the
     * boundary — the database session's timezone never does). Callers that want the
     * day the console prints beside a row must pass their own offset; 0 keeps the
     * UTC reading.
     */
    List<UsageStatsAggregator.UsageAggRow> aggregateUsage(GroupBy groupBy, UsageFilter filter, int tzOffsetMinutes);

    /**
     * The same aggregation on the <b>observed</b> token columns only (#1002): what
     * the gateway actually counted, without the deltas booked in the adjustment
     * ledger (#709).
     *
     * <p>
     * A quota watermark is a runtime verdict on observed usage, so it must read
     * this variant: booking a financial correction against a call changes what the
     * customer is billed, not what the gateway already measured, and letting the
     * correction move the watermark would silently raise or lower the scope's
     * quota. Requests, cost and pricing-gap columns are identical in both variants
     * — an adjustment never booked a call and never carried a price.
     * </p>
     */
    List<UsageStatsAggregator.UsageAggRow> aggregateObservedUsage(GroupBy groupBy, UsageFilter filter);

    /**
     * Aggregated cache-hit rows for the filter, one row per (group, product,
     * model). {@code cachedTokens} carries the usage of the cached response
     * (weighted mean across the group's cache entries), so the aggregator can value
     * the tokens the gateway saved. Bucket rules match
     * {@link #aggregateUsage(GroupBy, UsageFilter)}.
     */
    default List<UsageStatsAggregator.HitAggRow> aggregateHits(GroupBy groupBy, UsageFilter filter) {
        return aggregateHits(groupBy, filter, 0);
    }

    /** The same cache-hit aggregation in the caller's local day/month (#1050). */
    List<UsageStatsAggregator.HitAggRow> aggregateHits(GroupBy groupBy, UsageFilter filter, int tzOffsetMinutes);

    /**
     * Hourly usage buckets for the filter (#634): one row per (hour bucket,
     * project[, user/team]). Bucket boundaries are aligned to
     * {@code tzOffsetMinutes} from UTC so callers get natural-day hours in their
     * own timezone; the {@code from}/{@code to} window itself is matched in UTC
     * instants. Fully aggregated in SQL — no cost math involved.
     */
    List<HourlyUsageRow> aggregateHourly(HourlyDimension dimension, UsageFilter filter, int tzOffsetMinutes);

    /** Total matching {@code usage_event} rows (records pagination). */
    long countRecords(UsageFilter filter);

    /**
     * Raw usage-event rows for the filter, newest first, paged by position —
     * {@code offset}/{@code limit}, for jump-to-page. Never exposes prompt, code,
     * or model content — only counts and metadata.
     *
     * <p>
     * Ordered by {@code (occurred_at, id)} descending, i.e. a total order. A
     * positional window is still not a stable read of a live table: usage written
     * inside the window pushes rows back (they come out twice) and usage deleted
     * inside it pulls rows forward (they are never returned). Callers that walk
     * the whole list must use
     * {@link #findRecords(UsageFilter, int, Instant, UUID)} instead.
     * </p>
     */
    List<AdjustedUsageRow> findRecords(UsageFilter filter, long offset, int limit);

    /**
     * The same rows read through a keyset window (#1368): everything strictly
     * older than the row the previous page ended on. Passing the last row's
     * {@code occurredAt}/{@code id} — both {@code null} for the first page —
     * names a <em>row</em> rather than a position, so rows written or deleted
     * between two calls cannot shift the window: no row is handed out twice and
     * none is skipped while it exists.
     *
     * <p>
     * Same total order as the offset variant, and the id is what makes the window
     * itself well defined — the pair must identify exactly one row, which the
     * primary key guarantees.
     * </p>
     *
     * <p>
     * A row deleted between two pages is not returned by either flavour; that is
     * the honest reading ("no longer there"), not a lost read.
     * </p>
     */
    List<AdjustedUsageRow> findRecords(UsageFilter filter, int limit, Instant beforeOccurredAt, UUID beforeId);
}
