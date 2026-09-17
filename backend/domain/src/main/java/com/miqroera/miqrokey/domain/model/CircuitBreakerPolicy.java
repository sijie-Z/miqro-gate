package com.miqroera.miqrokey.domain.model;

import java.util.Set;

/**
 * Circuit-breaker policy surface shared by {@link McpCircuitBreaker} consumers
 * (#741): the F13 tool-call path reads it from the per-service
 * {@link McpResiliencePolicy}, and the LLM data plane builds it from gateway
 * configuration. The state machine only ever needs these members — extracting
 * them keeps the machine reusable without inheriting the MCP policy's retry
 * fields.
 */
public interface CircuitBreakerPolicy {

    /** Master switch; {@code false} means the breaker must not be consulted. */
    boolean breakerEnabled();

    /** Sliding statistics window in seconds. */
    int breakerWindowSeconds();

    /** Minimum samples in the window before a ratio can trip the breaker. */
    int breakerMinRequests();

    /** Whether error-ratio tripping is armed. */
    boolean breakerErrorEnabled();

    /** Error ratio (percent) that trips the breaker. */
    int breakerErrorRatio();

    /** Upstream statuses classified as errors for the ratio. */
    Set<Integer> breakerErrorStatusCodes();

    /** Whether slow-call-ratio tripping is armed. */
    boolean breakerSlowEnabled();

    /** Call duration (ms) above which a call counts as slow. */
    int breakerSlowCallMs();

    /** Slow-call ratio (percent) that trips the breaker. */
    int breakerSlowRatio();

    /** How long the breaker stays OPEN before probing. */
    int breakerOpenSeconds();

    /** Probes released per half-open window. */
    int breakerProbeCount();

    /** Consecutive probe successes that close the breaker. */
    int breakerProbeSuccess();
}
