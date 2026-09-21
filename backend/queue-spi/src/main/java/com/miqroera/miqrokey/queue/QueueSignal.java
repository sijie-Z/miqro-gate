package com.miqroera.miqrokey.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * One observation of usage-queue saturation: a window in which the gateway gave
 * up on {@code dropped} usage/lifecycle facts because the bounded queue was
 * full (F07, issue #245). The gateway cannot alert on this by itself — it has
 * no delivery channel — so the fact is persisted and the control plane
 * evaluates it with the same rules engine the other metric alerts use.
 *
 * <p>
 * {@code dropped} is a count of LOST facts, not a ratio. The queue is a process
 * resource, so the row carries the global signal; {@code tenantId} is the
 * tenant that owns the platform-level rule (the default tenant — see
 * {@link PostgresUsageEventBus#SIGNAL_TENANT_ID}). {@code queuedHighWater} and
 * {@code capacity} are diagnostic context for whoever reads the alert, not
 * inputs to the threshold.
 * </p>
 *
 * <p>
 * A saturated queue in {@link SaturationMode#WRITE_THROUGH} does not produce a
 * signal: the event is still persisted, the publisher merely stalls bounded.
 * This type measures loss, and that mode deliberately trades loss for latency.
 * </p>
 */
public record QueueSignal(UUID tenantId, Instant occurredAt, long dropped, long queuedHighWater, int capacity,
        SaturationMode saturationMode) {
}
