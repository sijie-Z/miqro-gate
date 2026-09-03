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
 * ({@code miqrokey.gateway.queue.capacity}, default 10 000). When full, the
 * behavior follows {@link SaturationMode}: {@code DROP} (default) rejects the
 * offer — the event is LOST but the gateway never blocks on the hot path;
 * {@code WRITE_THROUGH} (F35, emergency switch) routes the single event through
 * the dedicated writer executor and waits up to the configured timeout for the
 * idempotent write — audit integrity first, at the cost of a bounded stall.
 * Either way saturation is exposed via {@link #metrics()} and logged as a
 * high-priority warning, never silent.
 *
 * <h2>Threading</h2> publish() is lock-free (offer); scheduledFlush() runs on
 * the Spring scheduling thread and submits the actual flush to the dedicated
 * bounded writer scheduler ({@code miqrokey.gateway.queue.writer-threads},
 * default 4) — the scheduling thread is never blocked by a slow database, so
 * the route-snapshot refresh stays on cadence. An in-flight guard skips
 * overlapping flushes instead of piling up tasks. JDBC never runs on the
 * publishing thread: WRITE_THROUGH waits on the writer executor's completion.
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
    private final SaturationMode saturationMode;
    private final Duration writeThroughTimeout;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicLong totalPublished = new AtomicLong();
    private final AtomicLong totalPersisted = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong flushCount = new AtomicLong();
    private volatile Duration lastFlushDuration = Duration.ZERO;
    private volatile Instant lastFlushAt;

    public PostgresUsageEventBus(int capacity, int flushThreshold, UsageEventWriter writer, Scheduler writerScheduler,
            Clock clock, SaturationMode saturationMode, Duration writeThroughTimeout) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.flushThreshold = flushThreshold;
        this.writer = writer;
        this.writerScheduler = writerScheduler;
        this.clock = clock;
        this.saturationMode = saturationMode;
        this.writeThroughTimeout = writeThroughTimeout;
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
        if (queue.offer(event)) {
            totalPublished.incrementAndGet();
            return;
        }
        if (saturationMode == SaturationMode.WRITE_THROUGH && writeThrough(event)) {
            return;
        }
        totalDropped.incrementAndGet();
        log.warn("Usage event bus saturated; event dropped. queued={} mode={}", queue.size(), saturationMode);
    }

    /**
     * Emergency single-event write (F35): hands the event to the dedicated writer
     * executor and waits up to {@link #writeThroughTimeout} for the idempotent
     * write. The publishing thread stalls bounded, never executes JDBC itself.
     * Returns false when the write failed or timed out — the caller then counts the
     * drop as before.
     */
    private boolean writeThrough(Object event) {
        log.warn("Usage event bus saturated — emergency write-through. queued={}", queue.size());
        java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
        try {
            writerScheduler.schedule(() -> {
                try {
                    writeSingle(event);
                    totalPersisted.incrementAndGet();
                    done.complete(true);
                } catch (Exception e) {
                    log.warn("Emergency usage write-through failed", e);
                    done.complete(false);
                }
            });
            return done.get(writeThroughTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Emergency usage write-through timed out or was interrupted after {} ms",
                    writeThroughTimeout.toMillis());
            return false;
        }
    }

    /** Writes one event as a single-element batch through the shared writer. */
    private void writeSingle(Object event) {
        if (event instanceof UsageEvent ue) {
            writer.writeBatch(List.of(ue), List.of(), List.of(), List.of());
        } else if (event instanceof CacheHitEvent he) {
            writer.writeBatch(List.of(), List.of(he), List.of(), List.of());
        } else if (event instanceof RequestStartedEvent se) {
            writer.writeBatch(List.of(), List.of(), List.of(se), List.of());
        } else if (event instanceof RequestCompletedEvent ce) {
            writer.writeBatch(List.of(), List.of(), List.of(), List.of(ce));
        } else {
            throw new IllegalArgumentException("Unknown usage event type: " + event.getClass().getName());
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
