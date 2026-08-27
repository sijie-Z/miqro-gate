package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
import com.miqroera.miqrokey.domain.usage.UsageEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounded, asynchronous usage event bus. The gateway hot path only enqueues —
 * never writes to the database synchronously. A dedicated flush task drains the
 * queue in batches; saturation is measured and alerted, never silent.
 */
public interface UsageEventBus {

    /** Publishes an upstream/coalesced usage fact. Never blocks the caller. */
    void publish(UsageEvent event);

    /** Publishes a cache hit fact. Never blocks the caller. */
    void publish(CacheHitEvent event);

    /**
     * Publishes a request lifecycle start ({@code IN_FLIGHT} row). Never blocks the
     * caller.
     */
    void publish(RequestStartedEvent event);

    /**
     * Publishes a request lifecycle completion (finalizes the record exactly once).
     */
    void publish(RequestCompletedEvent event);

    /** Immediately drains the queue (shutdown, tests, admin flush). */
    void flush();

    /** Current queue and flush metrics. */
    QueueMetrics metrics();

    /** Snapshot of queue health for metrics/alerting. */
    record QueueMetrics(long queuedCount, long totalPublished, long totalPersisted, long totalDropped, long flushCount,
            Duration lastFlushDuration, Instant lastFlushAt) {

        public static QueueMetrics empty() {
            return new QueueMetrics(0, 0, 0, 0, 0, Duration.ZERO, null);
        }
    }
}
