package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.usage.UsageEvent;
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
        PROJECT, VIRTUAL_KEY, CACHE_LEVEL, DAY
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
            UUID subscriptionId, UUID providerProductId, String modelId, Instant from, Instant to) {

        /** Self-service shape: caller-scoped key set, no extra dimensions. */
        public UsageFilter(UUID tenantId, Set<UUID> virtualKeyIds, Instant from, Instant to) {
            this(tenantId, virtualKeyIds, null, null, null, null, null, null, from, to);
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

    /** Total matching {@code usage_event} rows (records pagination). */
    long countRecords(UsageFilter filter);

    /**
     * Raw usage-event rows for the filter, newest first, paged. Never exposes
     * prompt, code, or model content — only counts and metadata.
     */
    List<UsageEvent> findRecords(UsageFilter filter, long offset, int limit);
}
