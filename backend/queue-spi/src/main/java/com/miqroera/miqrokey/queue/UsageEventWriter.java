package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
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
 *
 * <p>
 * Request lifecycle records use a guarded upsert: starts are
 * {@code INSERT ... ON CONFLICT (started_at, gateway_request_id) DO NOTHING}
 * (status {@code IN_FLIGHT}); completions are a guarded update that only
 * transitions {@code IN_FLIGHT} rows, falling back to a direct insert when the
 * start row was never persisted — a retried flush can never double-finalize.
 * </p>
 */
public interface UsageEventWriter {

    /**
     * Persists one batch atomically (usage rows, hit rows + counters, lifecycle
     * starts, lifecycle completions).
     */
    void writeBatch(List<UsageEvent> usageEvents, List<CacheHitEvent> hitEvents,
            List<RequestStartedEvent> startedEvents, List<RequestCompletedEvent> completedEvents);
}
