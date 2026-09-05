package com.miqroera.miqrokey.domain.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * F13 tool-call circuit breaker (Tencent doc 134859), pure in-memory state
 * machine with three states:
 *
 * <pre>
 * CLOSED  — requests pass; a sliding window tracks error ratio and (when
 *           enabled) slow-call ratio; crossing a threshold with at least
 *           minRequests in the window opens the breaker.
 * OPEN    — requests fail fast (caller returns the degraded response, by
 *           default 503); after openSeconds the breaker probes again.
 * HALF_OPEN — up to probeCount probes pass; probeSuccess consecutive successes
 *           close the breaker, any failure re-opens it.
 * </pre>
 *
 * <p>
 * Error classification is supplied by the caller ({@code ok=false} for a
 * response status inside the configured trigger set or a transport failure;
 * slowness is {@code durationMs > slowCallMs}). Thread-safe; one instance per
 * (service, tool bucket) lives in the gateway's registry, replaced whenever the
 * policy changes.
 * </p>
 */
public final class McpCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    public enum Decision {
        ALLOWED, REJECTED, PROBE_ALLOWED
    }

    private record Sample(Instant at, boolean ok, long durationMs) {
    }

    private final McpResiliencePolicy policy;
    private final Clock clock;
    private final Deque<Sample> window = new ArrayDeque<>();
    private State state = State.CLOSED;
    private Instant openedUntil;
    private int probeSlots;
    private int probeSuccesses;

    public McpCircuitBreaker(McpResiliencePolicy policy, Clock clock) {
        if (!policy.breakerEnabled()) {
            throw new IllegalArgumentException("breaker policy must be enabled");
        }
        if (!policy.breakerErrorEnabled() && !policy.breakerSlowEnabled()) {
            throw new IllegalArgumentException("at least one breaker trigger must be enabled");
        }
        this.policy = policy;
        this.clock = clock;
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedUntil)) {
            enterHalfOpen();
        }
        return state;
    }

    /** Call before sending upstream. Never throws. */
    public synchronized Decision beforeCall() {
        Instant now = clock.instant();
        return switch (state) {
            case CLOSED -> Decision.ALLOWED;
            case OPEN -> {
                if (!now.isBefore(openedUntil)) {
                    enterHalfOpen();
                    // The transition itself consumes one probe slot: exactly
                    // probeCount probes are released while half-open.
                    yield consumeProbe();
                }
                yield Decision.REJECTED;
            }
            case HALF_OPEN -> probeSlots > 0 ? consumeProbe() : Decision.REJECTED;
        };
    }

    /**
     * Call after the request resolved (upstream answered or failed). Only the final
     * outcome of one gateway request is recorded — retried attempts are not
     * separately counted.
     */
    public synchronized void afterCall(boolean ok, long durationMs) {
        switch (state) {
            case CLOSED -> recordClosed(ok, durationMs);
            case HALF_OPEN -> {
                if (ok) {
                    if (++probeSuccesses >= policy.breakerProbeSuccess()) {
                        state = State.CLOSED;
                        window.clear();
                    }
                } else {
                    open(clock.instant());
                }
            }
            case OPEN -> {
                // Timer-only transition; nothing to record while open.
            }
        }
    }

    private Decision consumeProbe() {
        probeSlots--;
        return Decision.PROBE_ALLOWED;
    }

    private void enterHalfOpen() {
        state = State.HALF_OPEN;
        probeSlots = policy.breakerProbeCount();
        probeSuccesses = 0;
    }

    private void open(Instant now) {
        state = State.OPEN;
        openedUntil = now.plus(Duration.ofSeconds(policy.breakerOpenSeconds()));
        window.clear();
    }

    private void recordClosed(boolean ok, long durationMs) {
        Instant now = clock.instant();
        window.addLast(new Sample(now, ok, durationMs));
        Instant cutoff = now.minus(Duration.ofSeconds(policy.breakerWindowSeconds()));
        while (!window.isEmpty() && window.peekFirst().at().isBefore(cutoff)) {
            window.removeFirst();
        }
        int count = window.size();
        if (count < policy.breakerMinRequests()) {
            return; // min-request guard: low traffic must not false-trip
        }
        long errors = window.stream().filter(s -> !s.ok).count();
        long slow = window.stream().filter(s -> s.durationMs() > policy.breakerSlowCallMs()).count();
        boolean errorTrigger = policy.breakerErrorEnabled()
                && errors * 100L >= (long) policy.breakerErrorRatio() * count;
        boolean slowTrigger = policy.breakerSlowEnabled() && slow * 100L >= (long) policy.breakerSlowRatio() * count;
        if (errorTrigger || slowTrigger) {
            open(now);
        }
    }

    /** Current window call count (tests/observability). */
    public synchronized int windowSize() {
        return state == State.CLOSED ? window.size() : 0;
    }
}
