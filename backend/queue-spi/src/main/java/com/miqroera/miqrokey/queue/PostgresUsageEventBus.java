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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production usage event bus: bounded blocking queue drained on a fixed
 * schedule (default every 1s — #424: the 5s cadence let the red-line burst
 * (~2400 events/s) exceed the queue capacity within one period and drop). Each
 * flush drains the queue COMPLETELY in {@code flushThreshold}-sized batches
 * (the threshold is a batch size, not a drain limit — #417) into
 * {@link UsageEventWriter}.
 *
 * <h2>Saturation</h2> The queue is bounded
 * ({@code miqrokey.gateway.queue.capacity}, default 50 000 — #424: absorbs
 * multi-second writer stalls at the §10 red-line rate). When full, the behavior
 * follows {@link SaturationMode}: {@code DROP} (default) rejects the offer —
 * the event is LOST but the gateway never blocks on the hot path;
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
 *
 * <h2>Drop reporting (F07, #245)</h2> A dropped fact used to exist only as an
 * in-process counter no operator could query. The bus now also persists the
 * drop delta as a {@link QueueSignal} fact row, which the control plane
 * evaluates like any other metric alert. The reporting rides the same scheduled
 * writer executor as the flush — the hot path still only increments a counter,
 * never schedules work and never touches JDBC. The delta is claimed atomically
 * and given back if the write did not land, so a signal is eventually reported
 * exactly once and a healthy gateway (no drops) writes nothing.
 */
public final class PostgresUsageEventBus implements UsageEventBus {

    private static final Logger log = LoggerFactory.getLogger(PostgresUsageEventBus.class);

    /**
     * Tenant that owns the platform-level queue signal. The queue is a process
     * resource, not a per-request one, so its fact rows carry the default (seed)
     * tenant seeded by {@code V1__core_tables.sql}. Evaluation filters alert rules
     * by tenant, so this fires platform-level rules only: another tenant's rule
     * aggregates an always-empty window ({@code COALESCE(SUM(dropped), 0)} = 0) and
     * therefore stays quiet at any positive threshold. Thresholds are not validated
     * to be positive, so a rule with {@code threshold <= 0} still fires once per
     * dedupe window with {@code value = 0} — its own zero, never this count.
     * Single-tenant deployments (the v1 shape) see this as a plain global signal.
     */
    public static final UUID SIGNAL_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final BlockingQueue<Object> queue;
    private final UsageEventWriter writer;
    private final QueueSignalWriter signalWriter;
    private final Scheduler writerScheduler;
    private final Clock clock;
    private final int capacity;
    private final int flushThreshold;
    private final SaturationMode saturationMode;
    private final Duration writeThroughTimeout;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicLong totalPublished = new AtomicLong();
    private final AtomicLong totalPersisted = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong reportedDropped = new AtomicLong();
    private final AtomicLong queuedHighWater = new AtomicLong();
    private final AtomicLong flushCount = new AtomicLong();
    private volatile Duration lastFlushDuration = Duration.ZERO;
    private volatile Instant lastFlushAt;

    public PostgresUsageEventBus(int capacity, int flushThreshold, UsageEventWriter writer, Scheduler writerScheduler,
            Clock clock, SaturationMode saturationMode, Duration writeThroughTimeout, QueueSignalWriter signalWriter) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.capacity = capacity;
        this.flushThreshold = flushThreshold;
        this.writer = writer;
        this.signalWriter = signalWriter;
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
        sampleHighWater();
        log.warn("Usage event bus saturated; event dropped. queued={} mode={}", queue.size(), saturationMode);
    }

    /**
     * Records the deepest the queue got while it was losing events. Sampled only on
     * the drop paths — the healthy path pays nothing, and the only moment the
     * high-water mark is interesting is the moment it overflowed.
     */
    private void sampleHighWater() {
        queuedHighWater.accumulateAndGet(queue.size(), Math::max);
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
    @Scheduled(fixedDelayString = "${miqrokey.gateway.queue.flush-interval:1s}")
    public void scheduledFlush() {
        if (flushing.compareAndSet(false, true)) {
            try {
                writerScheduler.schedule(() -> {
                    try {
                        flush();
                    } finally {
                        flushing.set(false);
                    }
                });
            } catch (RuntimeException e) {
                // A rejected scheduling (writer queue saturated/disposed) must
                // not leave the in-flight guard set forever (#424): that would
                // silently stop ALL future flushes.
                flushing.set(false);
                throw e;
            }
        }
    }

    /**
     * Scheduled drop report, on the same cadence as the flush
     * ({@code miqrokey.gateway.queue.flush-interval}). Claims the drop delta
     * accumulated since the last reported signal and submits the write to the
     * dedicated writer scheduler — the scheduling thread never runs JDBC.
     *
     * <p>
     * A delta of zero returns immediately, so a healthy gateway writes no rows at
     * all rather than a heartbeat per interval.
     * </p>
     */
    @Scheduled(fixedDelayString = "${miqrokey.gateway.queue.flush-interval:1s}")
    public void scheduledSignalReport() {
        long observed = totalDropped.get();
        long claimed = reportedDropped.get();
        long delta = observed - claimed;
        if (delta <= 0 || !reportedDropped.compareAndSet(claimed, observed)) {
            return; // nothing was lost, or a concurrent report already claimed it
        }
        try {
            writerScheduler.schedule(() -> reportSignal(delta));
        } catch (RuntimeException e) {
            // Rejected scheduling (writer queue saturated or scheduler
            // disposed) must not swallow the loss: hand the delta back so the
            // next cycle reports it. There is no in-flight guard to reset here,
            // so the recovered failure is logged rather than rethrown.
            reportedDropped.addAndGet(-delta);
            log.warn("Queue saturation signal could not be scheduled; {} dropped events stay pending", delta, e);
        }
    }

    /**
     * Writes one drop-delta fact on the writer executor; restores it on failure.
     */
    private void reportSignal(long delta) {
        try {
            signalWriter.writeSignal(new QueueSignal(SIGNAL_TENANT_ID, clock.instant(), delta, queuedHighWater.get(),
                    capacity, saturationMode));
        } catch (Exception e) {
            // Claimed but not persisted: put the delta back so the next cycle
            // reports it together with anything dropped since.
            reportedDropped.addAndGet(-delta);
            log.warn("Queue saturation signal write failed; {} dropped events stay pending", delta, e);
        }
    }

    @Override
    public void flush() {
        // #417: drain the queue COMPLETELY, in flushThreshold-sized chunks per
        // writeBatch call. The previous single-chunk drain capped steady-state
        // throughput at flushThreshold/flush-interval (100/5s = 20 events/s) —
        // far below the §10 red-line ingest, so the bounded queue saturated and
        // DROP mode lost most usage events under 50 concurrent streams.
        // flushThreshold is the batch size, not a drain limit.
        while (flushChunk()) {
            // keep draining until the queue is empty (or a write failed)
        }
    }

    /**
     * One chunked drain + write; {@code false} when the queue emptied or a write
     * failed.
     */
    private boolean flushChunk() {
        List<Object> drained = new ArrayList<>(flushThreshold);
        queue.drainTo(drained, flushThreshold);
        if (drained.isEmpty()) {
            return false;
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
            return true;
        } catch (Exception e) {
            // Idempotent writes: re-enqueue for the next flush (bounded), and
            // stop this round instead of spinning on a failing database.
            for (Object item : drained) {
                if (!queue.offer(item)) {
                    totalDropped.incrementAndGet();
                    sampleHighWater();
                }
            }
            log.warn("Usage flush failed; {} events re-enqueued", drained.size());
            return false;
        }
    }

    @Override
    public QueueMetrics metrics() {
        return new QueueMetrics(queue.size(), totalPublished.get(), totalPersisted.get(), totalDropped.get(),
                flushCount.get(), lastFlushDuration, lastFlushAt);
    }
}
