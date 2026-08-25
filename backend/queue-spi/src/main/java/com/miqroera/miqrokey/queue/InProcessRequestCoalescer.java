package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.cache.CachedResponse;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-flight in-process coalescer: at most one leader per cache key, all
 * waiters share the leader's upstream call via a cached mono. The map entry
 * lives for the duration of one flight and is removed when the leader's work
 * terminates (success, error or cancellation); a late joiner then starts a
 * fresh flight.
 *
 * <p>
 * Waiters subscribe with {@code .timeout(waitTimeout)} and fall back to their
 * own call if the leader is too slow. Waiters receive the leader's
 * {@link CachedResponse} with its observed usage, so they emit COALESCED usage
 * events (never re-run the upstream call).
 * </p>
 */
public final class InProcessRequestCoalescer implements RequestCoalescer {

    private static final Logger log = LoggerFactory.getLogger(InProcessRequestCoalescer.class);

    private final Map<CacheKey, Flight> flights = new ConcurrentHashMap<>();

    @Override
    public Flight join(CacheKey key, Mono<CachedResponse> leaderWork, Duration waitTimeout) {
        return register(key, leaderWork, waitTimeout);
    }

    private Flight register(CacheKey key, Mono<CachedResponse> leaderWork, Duration waitTimeout) {
        // The map is only mutated after this point, so a synchronous upstream
        // (e.g. Mono.just) cannot terminate before holder[0] is assigned.
        Flight[] holder = new Flight[1];
        Mono<CachedResponse> tracked = leaderWork.doFinally(signal -> flights.remove(key, holder[0]));
        Mono<CachedResponse> shared = tracked.cache();
        Flight flight = new Flight(true, shared);
        holder[0] = flight;
        Flight raced = flights.putIfAbsent(key, flight);
        if (raced != null) {
            // Lost the race — join the winner's flight as a waiter.
            return new Flight(false, raced.shared().timeout(waitTimeout));
        }
        log.debug("Coalescer: leading flight for {}", key.hex());
        return flight;
    }

    /** Number of in-flight flights (metrics). */
    public int inFlight() {
        return flights.size();
    }

    /** Removes all flights (admin/test). */
    public void clear() {
        flights.clear();
    }
}
