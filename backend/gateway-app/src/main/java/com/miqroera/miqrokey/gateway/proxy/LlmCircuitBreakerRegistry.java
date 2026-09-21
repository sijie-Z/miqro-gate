package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.domain.model.McpCircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM-side circuit breaker registry (#741): one {@link McpCircuitBreaker} per
 * (provider product × upstream credential), created lazily on first use.
 *
 * <p>
 * Default off — when {@link LlmCircuitBreakerProperties#breakerEnabled()} is
 * false every decision is ALLOWED, no bucket is ever created and the counter
 * stays at zero, so the data plane behaves exactly as before the feature
 * existed. When enabled and the breaker is OPEN, calls fail fast with a
 * protocol-shaped {@code 503 circuit_open} before any upstream connection.
 * </p>
 *
 * <p>
 * Classification of an outcome is the registry's job, not the caller's: an
 * upstream status inside {@link LlmCircuitBreakerProperties#errorStatusCodes()}
 * or a transport failure counts as an error; a client cancel is not recorded at
 * all (the client's decision says nothing about the upstream, and the half-open
 * recycle in the state machine covers lost probe outcomes).
 * </p>
 */
@Component
public class LlmCircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreakerRegistry.class);

    private final LlmCircuitBreakerProperties properties;
    private final Clock clock;
    private final Counter rejected;
    private final ConcurrentHashMap<String, McpCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public LlmCircuitBreakerRegistry(LlmCircuitBreakerProperties properties, Clock clock, MeterRegistry registry) {
        this.properties = properties;
        this.clock = clock;
        // Zero-labelled by construction: records that the breaker fired, never
        // who fired it (configuration-reference §8: no user/key/product labels).
        this.rejected = Counter.builder("miqrokey_gateway_circuit_rejected_total")
                .description("Requests rejected by the LLM data-plane circuit breaker").register(registry);
    }

    /** The breaker for (product, credential); {@code null} when disabled. */
    public McpCircuitBreaker.Decision beforeCall(UUID productId, UUID credentialId, String requestId) {
        if (!properties.breakerEnabled()) {
            return McpCircuitBreaker.Decision.ALLOWED;
        }
        McpCircuitBreaker breaker = breakers.computeIfAbsent(bucketOf(productId, credentialId),
                key -> new McpCircuitBreaker(properties, clock));
        McpCircuitBreaker.Decision decision = breaker.beforeCall();
        if (decision == McpCircuitBreaker.Decision.REJECTED) {
            rejected.increment();
            log.warn("aigw.circuit_open requestId={} product={} credential={}", requestId, productId, credentialId);
        }
        return decision;
    }

    /**
     * Records the terminal outcome of one gateway request (retried attempts are not
     * separately counted; a client cancel is skipped). No-op when disabled or when
     * the bucket was never consulted.
     */
    public void afterCall(UUID productId, UUID credentialId, Integer upstreamStatus, boolean transportError,
            long durationMs) {
        if (!properties.breakerEnabled()) {
            return;
        }
        McpCircuitBreaker breaker = breakers.get(bucketOf(productId, credentialId));
        if (breaker == null) {
            return;
        }
        boolean ok = !transportError && upstreamStatus != null
                && !properties.errorStatusCodes().contains(upstreamStatus);
        breaker.afterCall(ok, durationMs);
    }

    /** Test/observability: number of live breaker buckets. */
    public int size() {
        return breakers.size();
    }

    /** Test seam: drop all bucket state (the registry is a context singleton). */
    public void reset() {
        breakers.clear();
    }

    private static String bucketOf(UUID productId, UUID credentialId) {
        return productId + "|" + credentialId;
    }
}
