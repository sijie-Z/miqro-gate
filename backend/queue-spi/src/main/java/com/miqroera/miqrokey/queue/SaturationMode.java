package com.miqroera.miqrokey.queue;

/**
 * What the usage event bus does when its bounded queue is full (F35, emergency
 * write-through). {@code DROP} preserves the pre-F35 behavior — the hot path
 * never waits and the event is counted lost. {@code WRITE_THROUGH} (the
 * emergency switch documented in architecture §5) routes the single event
 * through the dedicated writer executor and waits up to
 * {@code miqrokey.gateway.queue.write-through-timeout} for the idempotent write
 * to complete: integrity first at the cost of a bounded stall on the publishing
 * thread. JDBC itself still only ever runs on the writer executor.
 */
public enum SaturationMode {
    DROP, WRITE_THROUGH
}
