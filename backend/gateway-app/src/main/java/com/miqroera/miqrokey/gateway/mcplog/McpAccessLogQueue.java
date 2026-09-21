package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-process queue + periodic flush of MCP access log rows (F15),
 * modeled on the usage-event bus semantics. I19: after a batch is durably
 * written it is fanned out to the configured {@link McpAccessLogForwarder}s
 * (webhook / syslog) on the same flush thread — silently when none are
 * configured, drop-and-WARN when a sink fails, and never twice for a batch that
 * had to be requeued:
 *
 * <ul>
 * <li>{@code record()} only offers to a bounded queue — never blocks the
 * Reactor event loop and never throws.</li>
 * <li>Saturation drops the entry and counts it (throttled WARN).</li>
 * <li>A batch that failed for a <em>transient</em> reason is re-enqueued and
 * retried on the next flush; the idempotent writer
 * ({@code ON CONFLICT DO NOTHING} on {@code (tenant_id, gateway_request_id)})
 * makes retries safe.</li>
 * <li>A batch that failed for a <em>deterministic</em> reason (e.g. SQLSTATE
 * 22001 on an oversize value) is retried row by row instead, so the unwritable
 * row is abandoned and counted while its healthy neighbours still land (#1346)
 * — requeueing it whole would re-drain it with every later entry and stop all
 * access-log writes for good.</li>
 * <li>Flushes run on a dedicated single-thread scheduler, never on the event
 * loop or the shared scheduling thread.</li>
 * </ul>
 */
public final class McpAccessLogQueue implements McpAccessLogSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpAccessLogQueue.class);

    /** Warn about saturation at most once per this many drops. */
    private static final long DROP_LOG_THROTTLE = 100;

    private final ArrayBlockingQueue<McpAccessLogEntry> queue;
    private final McpAccessLogWriter writer;
    private final List<McpAccessLogForwarder> forwarders;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong unwritable = new AtomicLong();
    private final ScheduledExecutorService scheduler;

    public McpAccessLogQueue(int capacity, long flushIntervalMs, McpAccessLogWriter writer) {
        this(capacity, flushIntervalMs, writer, List.of());
    }

    public McpAccessLogQueue(int capacity, long flushIntervalMs, McpAccessLogWriter writer,
            List<McpAccessLogForwarder> forwarders) {
        if (capacity <= 0 || flushIntervalMs <= 0) {
            throw new IllegalArgumentException("capacity and flush-interval-ms must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = writer;
        this.forwarders = List.copyOf(forwarders);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-access-log-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void record(McpAccessLogEntry entry) {
        if (entry == null || !queue.offer(entry)) {
            long count = dropped.incrementAndGet();
            if (count % DROP_LOG_THROTTLE == 1) {
                log.warn("MCP access log queue saturated, {} entries dropped so far", count);
            }
        }
    }

    /** Number of entries dropped by queue saturation (observability + tests). */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * Number of entries abandoned because the writer rejected them for a reason no
     * retry can fix (#1346) — a bounded, per-request audit gap instead of a stalled
     * pipeline (observability + tests).
     */
    public long unwritableCount() {
        return unwritable.get();
    }

    /**
     * Drains everything currently queued into the writer (package-private for
     * tests).
     */
    void flushNow() {
        flush();
    }

    private void flush() {
        List<McpAccessLogEntry> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        List<McpAccessLogEntry> written;
        try {
            writer.writeBatch(batch);
            written = batch;
        } catch (Exception e) {
            if (!isDeterministic(e)) {
                // Audit rows are worth more than the usage queue's drop semantics:
                // requeue for the next flush (idempotent writes make retries safe).
                // Re-offer failures (queue became full again) fall back to drop + count.
                requeue(batch);
                log.error("MCP access log batch write of {} rows failed; requeued for retry", batch.size(), e);
                return;
            }
            // #1346: this batch can never succeed as a whole, so requeueing it
            // would re-drain it together with every later entry — and the
            // pipeline would never write anything again. Give the healthy rows
            // their own attempt and abandon only the ones that are truly
            // unwritable.
            log.error("MCP access log batch write of {} rows failed deterministically; retrying row by row",
                    batch.size(), e);
            written = writeOneByOne(batch);
            if (written.isEmpty()) {
                return;
            }
        }
        // I19: fan out only AFTER the durable write — a requeued batch is never
        // forwarded twice, and a failing sink never affects the audit rows.
        for (McpAccessLogForwarder forwarder : forwarders) {
            try {
                forwarder.forward(written);
            } catch (Exception e) {
                log.warn("MCP access log forwarder {} failed: {}", forwarder.name(), e.getMessage());
            }
        }
    }

    private void requeue(List<McpAccessLogEntry> batch) {
        for (McpAccessLogEntry entry : batch) {
            if (!queue.offer(entry)) {
                dropped.incrementAndGet();
            }
        }
    }

    /**
     * Last resort for a batch that cannot be written as a unit: retry every entry
     * on its own so one bad row no longer takes its healthy neighbours down with
     * it, and return the entries that made it (they are the ones to forward).
     */
    private List<McpAccessLogEntry> writeOneByOne(List<McpAccessLogEntry> batch) {
        List<McpAccessLogEntry> written = new ArrayList<>(batch.size());
        for (McpAccessLogEntry entry : batch) {
            try {
                writer.writeBatch(List.of(entry));
                written.add(entry);
            } catch (Exception e) {
                long count = unwritable.incrementAndGet();
                // Only gateway-generated fields are logged: the entry's
                // caller-controlled values must not reach a log line.
                log.error(
                        "MCP access log row abandoned (unwritable after every retry): requestId={} status={}"
                                + " service={} — {} rows abandoned so far",
                        entry.gatewayRequestId(), entry.status(), entry.serviceName(), count, e);
            }
        }
        return written;
    }

    /**
     * Whether a retry can never fix this failure: PostgreSQL data exceptions (class
     * 22 — {@code 22001} value too long, {@code 22003} numeric overflow, …) and
     * integrity constraint violations (class 23). Everything else — connection loss
     * (class 08), deadlock/serialization (class 40), timeouts, an unrecognized
     * error — keeps the requeue path, so a transient outage is never converted into
     * data loss.
     */
    private static boolean isDeterministic(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && (state.startsWith("22") || state.startsWith("23"))) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    @Override
    public void close() {
        // #451: stop the scheduler first, then drain whatever the queue still
        // holds — a graceful stop must not lose audit rows it already accepted.
        // An in-flight flush interrupted by the shutdown re-queues its batch
        // (idempotent writes), which the final drain then picks up.
        scheduler.shutdownNow();
        flush();
    }
}
