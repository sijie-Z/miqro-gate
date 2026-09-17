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
        PROJECT, VIRTUAL_KEY, CACHE_LEVEL, DAY,
        /** I15 (doc 134892): per-consumer dimension (label = username). */
        USER,
        /** I15: per-model dimension (label = model id). */
        MODEL,
        /** I15: calendar-month granularity (label = {@code YYYY-MM}). */
        MONTH,
        /**
         * Per-team dimension (label = team name, joined through the member's virtual
         * keys). A user in several teams is counted in each team's total — team totals
         * are attribution views, not a partition.
         */
        TEAM
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
     * {@link UsageStatsAggregator#aggregate}.
     */
    List<UsageStatsAggregator.UsageAggRow> aggregateUsage(GroupBy groupBy, UsageFilter filter);

    /**
     * Aggregated cache-hit rows for the filter, one row per (group, product,
     * model). {@code cachedTokens} carries the usage of the cached response
     * (weighted mean across the group's cache entries), so the aggregator can value
     * the tokens the gateway saved.
     */
    List<UsageStatsAggregator.HitAggRow> aggregateHits(GroupBy groupBy, UsageFilter filter);

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
     * Raw usage-event rows for the filter, newest first, paged. Never exposes
     * prompt, code, or model content — only counts and metadata.
     */
    List<AdjustedUsageRow> findRecords(UsageFilter filter, long offset, int limit);
}
