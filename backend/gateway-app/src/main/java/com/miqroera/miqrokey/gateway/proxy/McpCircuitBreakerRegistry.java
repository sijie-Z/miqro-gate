package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.domain.model.McpCircuitBreaker;
import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-(service, bucket) circuit breaker instances for the MCP egress (F13).
 * Buckets follow the Tencent doc 134859 per-tool isolation where it exists in
 * this data plane: {@code tools/call} buckets by tool name, every other
 * envelope method (initialize / tools/list / …) buckets by method. A changed
 * policy replaces the instance (state reset) — configuration edits take effect
 * on the next snapshot refresh. Instance count is bounded by (services × tools
 * + methods); with the per-tenant topology this stays in the hundreds.
 */
public final class McpCircuitBreakerRegistry {

    private final Clock clock;
    private final ConcurrentMap<String, Holder> breakers = new ConcurrentHashMap<>();

    public McpCircuitBreakerRegistry(Clock clock) {
        this.clock = clock;
    }

    public record Holder(McpResiliencePolicy policy, McpCircuitBreaker breaker) {
    }

    /**
     * Returns the breaker for (service, bucket), creating or replacing it when no
     * instance matches the current policy.
     */
    public Holder get(UUID serviceId, String bucket, McpResiliencePolicy policy) {
        String key = serviceId + "|" + bucket;
        Holder holder = breakers.get(key);
        if (holder != null && holder.policy().equals(policy)) {
            return holder;
        }
        Holder fresh = new Holder(policy, new McpCircuitBreaker(policy, clock));
        Holder raced = breakers.putIfAbsent(key, fresh);
        if (raced == null) {
            return fresh;
        }
        // Another thread installed an instance in the meantime: prefer the one
        // matching the current policy, otherwise replace it.
        breakers.computeIfPresent(key, (k, existing) -> existing.policy().equals(policy) ? existing : fresh);
        return breakers.get(key);
    }

    /** Test/observability: number of live breaker instances. */
    public int size() {
        return breakers.size();
    }
}
