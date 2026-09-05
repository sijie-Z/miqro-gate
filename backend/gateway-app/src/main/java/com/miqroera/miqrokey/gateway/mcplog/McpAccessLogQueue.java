package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-process queue + periodic flush of MCP access log rows (F15),
 * modeled on the usage-event bus semantics:
 *
 * <ul>
 * <li>{@code record()} only offers to a bounded queue — never blocks the
 * Reactor event loop and never throws.</li>
 * <li>Saturation drops the entry and counts it (throttled WARN).</li>
 * <li>A failed batch is re-enqueued and retried on the next flush; the
 * idempotent writer ({@code ON CONFLICT DO NOTHING} on
 * {@code (tenant_id, gateway_request_id)}) makes retries safe.</li>
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
    private final AtomicLong dropped = new AtomicLong();
    private final ScheduledExecutorService scheduler;

    public McpAccessLogQueue(int capacity, long flushIntervalMs, McpAccessLogWriter writer) {
        if (capacity <= 0 || flushIntervalMs <= 0) {
            throw new IllegalArgumentException("capacity and flush-interval-ms must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = writer;
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
        try {
            writer.writeBatch(batch);
        } catch (Exception e) {
            // Audit rows are worth more than the usage queue's drop semantics:
            // requeue for the next flush (idempotent writes make retries safe).
            // Re-offer failures (queue became full again) fall back to drop + count.
            for (McpAccessLogEntry entry : batch) {
                if (!queue.offer(entry)) {
                    dropped.incrementAndGet();
                }
            }
            log.error("MCP access log batch write of {} rows failed; requeued for retry", batch.size(), e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
