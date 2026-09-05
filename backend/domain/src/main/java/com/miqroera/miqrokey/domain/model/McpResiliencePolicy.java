package com.miqroera.miqrokey.domain.model;

import java.util.Set;

/**
 * F12 tool-call retry policy (Tencent doc 134831 semantics, adapted from the
 * HTTP-to-MCP tool granularity to the service egress): default OFF; retries
 * only happen before the first upstream response byte reaches the caller;
 * non-idempotent tool HTTP methods (POST/PUT/PATCH) require an explicit
 * idempotency confirmation before retries may apply to their calls.
 *
 * <p>
 * Stored per MCP service in {@code mcp_resilience_policy} (V30) and carried by
 * the route snapshot. A null policy (or one with everything disabled) means the
 * data plane behaves exactly as before.
 * </p>
 */
public record McpResiliencePolicy(boolean retryEnabled, int retryMax, Set<RetryCondition> retryConditions,
        boolean idempotencyConfirmed, boolean breakerEnabled, int breakerWindowSeconds, int breakerMinRequests,
        boolean breakerErrorEnabled, int breakerErrorRatio, Set<Integer> breakerErrorStatusCodes,
        boolean breakerSlowEnabled, int breakerSlowCallMs, int breakerSlowRatio, int breakerOpenSeconds,
        int breakerProbeCount, int breakerProbeSuccess, boolean breakerSkipRetry, long version) {

    public enum RetryCondition {
        SERVER_5XX, CONNECTION_FAILURE, TIMEOUT
    }

    /** Tencent defaults for the breaker's sliding statistics window. */
    public static final int DEFAULT_WINDOW_SECONDS = 10;
    public static final int DEFAULT_MIN_REQUESTS = 10;
    public static final int DEFAULT_ERROR_RATIO = 50;
    public static final int DEFAULT_SLOW_CALL_MS = 3000;
    public static final int DEFAULT_SLOW_RATIO = 80;
    public static final int DEFAULT_OPEN_SECONDS = 30;
    public static final int DEFAULT_PROBE_COUNT = 3;
    public static final int DEFAULT_PROBE_SUCCESS = 2;

    /** Fully-disabled default: data plane behaves exactly as before. */
    public static McpResiliencePolicy disabled() {
        return new McpResiliencePolicy(false, 1, Set.of(), false, false, DEFAULT_WINDOW_SECONDS, DEFAULT_MIN_REQUESTS,
                true, DEFAULT_ERROR_RATIO, Set.of(500, 502, 503, 504), false, DEFAULT_SLOW_CALL_MS, DEFAULT_SLOW_RATIO,
                DEFAULT_OPEN_SECONDS, DEFAULT_PROBE_COUNT, DEFAULT_PROBE_SUCCESS, true, 0);
    }

    public McpResiliencePolicy {
        retryConditions = Set.copyOf(retryConditions);
        breakerErrorStatusCodes = Set.copyOf(breakerErrorStatusCodes);
        if (retryMax < 1 || retryMax > 5) {
            throw new IllegalArgumentException("retryMax must be 1..5");
        }
        if (breakerWindowSeconds < 1 || breakerWindowSeconds > 60) {
            throw new IllegalArgumentException("breakerWindowSeconds must be 1..60");
        }
        if (breakerMinRequests < 1 || breakerMinRequests > 100) {
            throw new IllegalArgumentException("breakerMinRequests must be 1..100");
        }
        if (breakerErrorRatio < 1 || breakerErrorRatio > 100 || breakerSlowRatio < 1 || breakerSlowRatio > 100) {
            throw new IllegalArgumentException("breaker ratios must be 1..100");
        }
        if (breakerSlowCallMs < 100 || breakerSlowCallMs > 60000) {
            throw new IllegalArgumentException("breakerSlowCallMs must be 100..60000");
        }
        if (breakerOpenSeconds < 5 || breakerOpenSeconds > 600) {
            throw new IllegalArgumentException("breakerOpenSeconds must be 5..600");
        }
        if (breakerProbeCount < 1 || breakerProbeCount > 10 || breakerProbeSuccess < 1
                || breakerProbeSuccess > breakerProbeCount) {
            throw new IllegalArgumentException("breakerProbeSuccess must be 1..breakerProbeCount(1..10)");
        }
        for (int code : breakerErrorStatusCodes) {
            if (code < 400 || code > 599) {
                throw new IllegalArgumentException("breaker error status codes must be 400..599");
            }
        }
        if (breakerErrorStatusCodes.size() > 32) {
            throw new IllegalArgumentException("at most 32 breaker error status codes");
        }
    }

    /** True when any resilience feature is enabled. */
    public boolean anyEnabled() {
        return retryEnabled || breakerEnabled;
    }
}
