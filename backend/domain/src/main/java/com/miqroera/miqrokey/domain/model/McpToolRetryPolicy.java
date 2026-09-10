package com.miqroera.miqrokey.domain.model;

import java.util.Set;

/**
 * F12 retry override at TOOL granularity (issue #360, I13, raw doc 12): a tool
 * with a row in {@code mcp_tool_retry_policy} (V46) replaces the service
 * policy's retry fields for its own {@code tools/call} traffic; the breaker
 * stays service-level. No row = no override (the service policy applies
 * unchanged).
 */
public record McpToolRetryPolicy(boolean retryEnabled, int retryMax,
        Set<McpResiliencePolicy.RetryCondition> retryConditions, boolean idempotencyConfirmed, long version) {

    public McpToolRetryPolicy {
        retryConditions = Set.copyOf(retryConditions);
        if (retryMax < 1 || retryMax > 5) {
            throw new IllegalArgumentException("retryMax must be 1..5");
        }
        if (retryEnabled && retryConditions.isEmpty()) {
            throw new IllegalArgumentException("at least one retry condition is required when retries are enabled");
        }
    }

    /** Fully-disabled default: the service-level policy is unchanged. */
    public static McpToolRetryPolicy disabled() {
        return new McpToolRetryPolicy(false, 1, Set.of(), false, 0);
    }
}
