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
 * retried flush never double-counts. {@code usage_event} deliberately names no
 * conflict target: its rows are deduplicated by the partial index
 * {@code (tenant_id, provider_request_id) WHERE provider_request_id IS NOT NULL},
 * which by definition does not arbitrate rows that carry no upstream request id
 * (COALESCED / cache hits) — a replay of such a row can only conflict on the
 * {@code id} primary key, so naming the partial index as the arbiter turns that
 * replay into a hard error instead of a no-op (#887).
 * {@code cache_hit_event} conflicts on
 * {@code (tenant_id, cache_key, level, occurred_at)}.
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
