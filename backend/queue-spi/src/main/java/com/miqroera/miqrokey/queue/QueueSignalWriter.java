package com.miqroera.miqrokey.queue;

/**
 * Persists {@link QueueSignal} saturation facts. Called only from the dedicated
 * writer scheduler — never from the gateway hot path, which is the same
 * red line {@link UsageEventWriter} obeys.
 *
 * <p>
 * {@link InMemoryUsageEventBus} has no writer at all: with persistence disabled
 * the gateway runs DB-free and there is no fact table to write to.
 * </p>
 */
public interface QueueSignalWriter {

    /**
     * Writes one saturation fact. Implementations must be idempotent per
     * signal: the bus restores an unwritten delta and retries it on the next
     * cycle, so a failed call must not have left a row behind.
     */
    void writeSignal(QueueSignal signal);
}
