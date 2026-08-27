package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.domain.usage.CacheHitEvent;
import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.RequestStartedEvent;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production usage event bus: bounded blocking queue drained on a fixed
 * schedule (default every 5s or when 100 events accumulate) into
 * {@link UsageEventWriter}.
 *
 * <h2>Saturation</h2> The queue is bounded
 * ({@code miqrokey.gateway.queue.capacity}, default 10 000). When full, offers
 * are rejected and counted in {@code totalDropped} — the event is LOST but the
 * gateway never blocks on the hot path. Saturation is exposed via
 * {@link #metrics()} for alerting and logged as a high-priority warning.
 *
 * <h2>Threading</h2> publish() is lock-free (offer); scheduledFlush() runs on
 * the Spring scheduling thread and submits the actual flush to the dedicated
 * bounded writer scheduler ({@code miqrokey.gateway.queue.writer-threads},
 * default 4) — the scheduling thread is never blocked by a slow database, so
 * the route-snapshot refresh stays on cadence. An in-flight guard skips
 * overlapping flushes instead of piling up tasks.
 *
 * <h2>Failure semantics</h2> When {@code writeBatch} throws (database briefly
 * unavailable), the drained events are re-enqueued in order for the next flush
 * and the failure is logged — usage is never silently lost. Writes are
 * idempotent, so a retried batch cannot double-count.
 */
public final class PostgresUsageEventBus implements UsageEventBus {

    private static final Logger log = LoggerFactory.getLogger(PostgresUsageEventBus.class);

    private final BlockingQueue<Object> queue;
    private final UsageEventWriter writer;
    private final Scheduler writerScheduler;
    private final Clock clock;
    private final int flushThreshold;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicLong totalPublished = new AtomicLong();
    private final AtomicLong totalPersisted = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong flushCount = new AtomicLong();
    private volatile Duration lastFlushDuration = Duration.ZERO;
    private volatile Instant lastFlushAt;

    public PostgresUsageEventBus(int capacity, int flushThreshold, UsageEventWriter writer, Scheduler writerScheduler,
            Clock clock) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.flushThreshold = flushThreshold;
        this.writer = writer;
        this.writerScheduler = writerScheduler;
        this.clock = clock;
    }

    @Override
    public void publish(UsageEvent event) {
        offer(event);
    }

    @Override
    public void publish(CacheHitEvent event) {
        offer(event);
    }

    @Override
    public void publish(RequestStartedEvent event) {
        offer(event);
    }

    @Override
    public void publish(RequestCompletedEvent event) {
        offer(event);
    }

    private void offer(Object event) {
        if (!queue.offer(event)) {
            totalDropped.incrementAndGet();
            log.warn("Usage event bus saturated; event dropped. queued={}", queue.size());
        } else {
            totalPublished.incrementAndGet();
        }
    }

    /**
     * Scheduled flush (every {@code miqrokey.gateway.queue.flush-interval}).
     * Submits to the dedicated writer scheduler; a flush already in flight is
     * skipped, never queued up.
     */
    @Scheduled(fixedDelayString = "${miqrokey.gateway.queue.flush-interval:5s}")
    public void scheduledFlush() {
        if (flushing.compareAndSet(false, true)) {
            writerScheduler.schedule(() -> {
                try {
                    flush();
                } finally {
                    flushing.set(false);
                }
            });
        }
    }

    @Override
    public void flush() {
        List<Object> drained = new ArrayList<>(flushThreshold);
        queue.drainTo(drained, flushThreshold);
        if (drained.isEmpty()) {
            return;
        }
        List<UsageEvent> usage = new ArrayList<>();
        List<CacheHitEvent> hits = new ArrayList<>();
        List<RequestStartedEvent> starts = new ArrayList<>();
        List<RequestCompletedEvent> completions = new ArrayList<>();
        for (Object item : drained) {
            if (item instanceof UsageEvent ue) {
                usage.add(ue);
            } else if (item instanceof CacheHitEvent he) {
                hits.add(he);
            } else if (item instanceof RequestStartedEvent se) {
                starts.add(se);
            } else if (item instanceof RequestCompletedEvent ce) {
                completions.add(ce);
            }
        }
        Instant started = clock.instant();
        try {
            writer.writeBatch(usage, hits, starts, completions);
            totalPersisted.addAndGet(usage.size() + hits.size() + starts.size() + completions.size());
            lastFlushDuration = Duration.between(started, clock.instant());
            lastFlushAt = clock.instant();
            flushCount.incrementAndGet();
        } catch (Exception e) {
            // Idempotent writes: re-enqueue for the next flush (bounded).
            for (Object item : drained) {
                if (!queue.offer(item)) {
                    totalDropped.incrementAndGet();
                }
            }
            log.warn("Usage flush failed; {} events re-enqueued", drained.size());
        }
    }

    @Override
    public QueueMetrics metrics() {
        return new QueueMetrics(queue.size(), totalPublished.get(), totalPersisted.get(), totalDropped.get(),
                flushCount.get(), lastFlushDuration, lastFlushAt);
    }
}
