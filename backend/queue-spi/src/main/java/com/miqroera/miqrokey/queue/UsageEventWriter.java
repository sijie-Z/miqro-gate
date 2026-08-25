package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.UsageEvent;

import java.util.List;

/**
 * Idempotent batch persistence for usage facts.
 *
 * <p>
 * Implementations must use {@code INSERT ... ON CONFLICT DO NOTHING} so that a
 * retried flush never double-counts: usage_event conflicts on
 * {@code (tenant_id, provider_request_id)} (partial, non-null rows only),
 * cache_hit_event on {@code (tenant_id, cache_key, level, occurred_at)}.
 * </p>
 */
public interface UsageEventWriter {

    /** Persists one batch atomically (usage rows, then hit rows + counters). */
    void writeBatch(List<UsageEvent> usageEvents, List<CacheHitEvent> hitEvents);
}
