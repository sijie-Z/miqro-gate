package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hard-deadline wrapper around a sink (#401): forwarder failures are caught and
 * throttled by the queue, but a HANG is not an exception — a TCP syslog
 * receiver that accepts the connection and stops reading blocks
 * {@code OutputStream.write} indefinitely (Java's blocking sockets have no
 * portable write timeout and a blocked write ignores interrupts). On the single
 * flush thread that would stop every later batch from reaching the database and
 * saturate the bounded queue into drops. This wrapper runs each delivery on a
 * per-sink daemon thread with a wall-clock deadline; a sink that keeps timing
 * out is skipped for a cooldown (no retry storm, no flush-thread tax) and
 * probed again after it — the flush pipeline stays alive throughout.
 */
final class TimeBoundedForwarder implements McpAccessLogForwarder {

    private static final Logger log = LoggerFactory.getLogger(TimeBoundedForwarder.class);

    /** Consecutive timeouts that trip the cooldown skip. */
    static final int BREAKER_THRESHOLD = 3;

    /**
     * Default cooldown after the breaker trips; the next attempt probes the sink.
     */
    static final long BREAKER_COOLDOWN_MS = 60_000;

    private final McpAccessLogForwarder delegate;
    private final long timeoutMs;
    private final long cooldownMs;
    private final ExecutorService executor;
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger();
    private volatile long cooldownUntil;

    TimeBoundedForwarder(McpAccessLogForwarder delegate, long timeoutMs) {
        this(delegate, timeoutMs, BREAKER_COOLDOWN_MS);
    }

    TimeBoundedForwarder(McpAccessLogForwarder delegate, long timeoutMs, long cooldownMs) {
        if (timeoutMs <= 0 || cooldownMs <= 0) {
            throw new IllegalArgumentException("forwarder deadline and cooldown must be > 0");
        }
        this.delegate = delegate;
        this.timeoutMs = timeoutMs;
        this.cooldownMs = cooldownMs;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-log-forward-" + delegate.name());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void forward(List<McpAccessLogEntry> batch) {
        if (System.currentTimeMillis() < cooldownUntil) {
            return; // tripped sink: skip silently until the cooldown probe
        }
        Future<?> task = executor.submit(() -> delegate.forward(batch));
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS);
            consecutiveTimeouts.set(0);
        } catch (TimeoutException e) {
            // The worker may stay stuck for good (a blocked socket write is not
            // interruptible); it holds the single sink thread and later tasks
            // queue behind it — the cooldown contains that. cancel(true) still
            // reclaims interruptible waits (HTTP send).
            task.cancel(true);
            int streak = consecutiveTimeouts.incrementAndGet();
            if (streak >= BREAKER_THRESHOLD) {
                cooldownUntil = System.currentTimeMillis() + cooldownMs;
                consecutiveTimeouts.set(0);
                log.warn("MCP access log forwarder {} timed out {} times in a row; skipping for {} ms", delegate.name(),
                        streak, cooldownMs);
            } else {
                log.warn("MCP access log forwarder {} delivery timed out after {} ms ({}/{})", delegate.name(),
                        timeoutMs, streak, BREAKER_THRESHOLD);
            }
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // delegate.forward never throws by contract; reset defensively.
            consecutiveTimeouts.set(0);
        }
    }

    /** Current consecutive-timeout streak (observability + tests). */
    int timeoutStreak() {
        return consecutiveTimeouts.get();
    }

    /** Whether the breaker cooldown is currently skipping deliveries (tests). */
    boolean inCooldown() {
        return System.currentTimeMillis() < cooldownUntil;
    }
}
