package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.cache.CachedResponse;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * In-process request coalescing SPI: identical in-flight requests share one
 * upstream call. DISABLED by default (ADR-0008); the gateway engages it only
 * when {@code miqrokey.gateway.coalescer.enabled=true}.
 *
 * <p>
 * Waiters receive the leader's {@link CachedResponse} (which carries the
 * observed usage, so they can emit COALESCED usage events). If the leader fails
 * or the wait times out, the waiter falls back to doing its own call.
 * </p>
 */
public interface RequestCoalescer {

    /**
     * Joins or leads a flight for the given cache key.
     *
     * @param key
     *            normalized request identity
     * @param leaderWork
     *            the upstream/cache work to run if this caller becomes leader
     * @param waitTimeout
     *            how long a waiter waits for the leader before falling back
     * @return flight: {@code leader=true} means the caller must subscribe to the
     *         returned mono; {@code leader=false} means subscribe and merge
     *         (COALESCED), or on timeout/failure re-run the work
     */
    Flight join(CacheKey key, Mono<CachedResponse> leaderWork, Duration waitTimeout);

    /** Result of joining a flight. */
    record Flight(boolean leader, Mono<CachedResponse> shared) {
    }
}
