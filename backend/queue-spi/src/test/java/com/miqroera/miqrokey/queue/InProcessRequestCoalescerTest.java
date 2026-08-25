package com.miqroera.miqrokey.queue;

import com.miqroera.miqrokey.cache.CachedResponse;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the in-process single-flight coalescer: at most one leader
 * per cache key, waiters share the leader's result, flights are removed on
 * termination.
 */
@DisplayName("InProcessRequestCoalescer")
class InProcessRequestCoalescerTest {

    private static final CacheKey KEY_A = CacheKey.from(new byte[32]);
    private static final CacheKey KEY_B = CacheKey.from(java.util.Arrays.copyOf(new byte[]{1}, 32));
    private static final CachedResponse RESPONSE = new CachedResponse(200, "application/json",
            java.util.Map.of("x-test", java.util.List.of("v")), new byte[]{1, 2, 3},
            com.miqroera.miqrokey.domain.usage.TokenBucket.EMPTY, true);

    private final InProcessRequestCoalescer coalescer = new InProcessRequestCoalescer();

    @Nested
    @DisplayName("Single-flight semantics")
    class SingleFlight {

        @Test
        @DisplayName("should run the leader's work exactly once for concurrent waiters")
        void shouldRunWorkOnce() {
            AtomicInteger workRuns = new AtomicInteger();
            Mono<CachedResponse> work = Mono.defer(() -> {
                workRuns.incrementAndGet();
                return Mono.just(RESPONSE);
            });
            Duration wait = Duration.ofSeconds(2);

            RequestCoalescer.Flight leaderFlight = coalescer.join(KEY_A, work, wait);
            assertThat(leaderFlight.leader()).isTrue();
            leaderFlight.shared().block(Duration.ofSeconds(2));
            assertThat(workRuns).hasValue(1);

            // A joiner after completion starts a fresh flight (entry removed).
            RequestCoalescer.Flight second = coalescer.join(KEY_A, work, wait);
            assertThat(second.leader()).isTrue();
            second.shared().block(Duration.ofSeconds(2));
            assertThat(workRuns).hasValue(2);
        }

        @Test
        @DisplayName("should share the leader's response with waiters")
        void shouldShareWithWaiters() throws Exception {
            AtomicInteger workRuns = new AtomicInteger();
            Mono<CachedResponse> work = Mono.defer(() -> {
                workRuns.incrementAndGet();
                return Mono.delay(Duration.ofMillis(100)).thenReturn(RESPONSE);
            });
            Duration wait = Duration.ofSeconds(5);

            RequestCoalescer.Flight leaderFlight = coalescer.join(KEY_A, work, wait);
            assertThat(leaderFlight.leader()).isTrue();
            leaderFlight.shared().subscribe();

            Thread.sleep(20); // let the leader subscribe before the waiter joins
            RequestCoalescer.Flight waiterFlight = coalescer.join(KEY_A, work, wait);
            assertThat(waiterFlight.leader()).isFalse();

            CachedResponse shared = waiterFlight.shared().block(Duration.ofSeconds(5));
            assertThat(shared).isEqualTo(RESPONSE);
            assertThat(workRuns).hasValue(1);
            assertThat(coalescer.inFlight()).isZero();
        }

        @Test
        @DisplayName("should keep separate flights for different keys")
        void shouldKeepSeparateFlightsPerKey() throws Exception {
            AtomicInteger runs = new AtomicInteger();
            Mono<CachedResponse> work = Mono.defer(() -> {
                runs.incrementAndGet();
                return Mono.delay(Duration.ofMillis(100)).thenReturn(RESPONSE);
            });
            Duration wait = Duration.ofSeconds(5);

            RequestCoalescer.Flight leaderA = coalescer.join(KEY_A, work, wait);
            leaderA.shared().subscribe();
            Thread.sleep(20);
            RequestCoalescer.Flight leaderB = coalescer.join(KEY_B, work, wait);
            assertThat(leaderB.leader()).isTrue();
            leaderB.shared().block(Duration.ofSeconds(5));
            leaderA.shared().block(Duration.ofSeconds(5));
            assertThat(runs).hasValue(2);
        }
    }

    @Nested
    @DisplayName("Waiter fallback")
    class WaiterFallback {

        @Test
        @DisplayName("should time out a slow leader so the waiter can fall back")
        void shouldTimeoutSlowLeader() throws Exception {
            AtomicInteger workRuns = new AtomicInteger();
            Mono<CachedResponse> slowWork = Mono.defer(() -> {
                workRuns.incrementAndGet();
                return Mono.delay(Duration.ofSeconds(2)).thenReturn(RESPONSE);
            });
            Duration shortWait = Duration.ofMillis(100);

            RequestCoalescer.Flight leaderFlight = coalescer.join(KEY_A, slowWork, shortWait);
            leaderFlight.shared().subscribe();
            Thread.sleep(20);

            RequestCoalescer.Flight waiterFlight = coalescer.join(KEY_A, slowWork, shortWait);
            assertThat(waiterFlight.leader()).isFalse();
            // block(Duration) wraps the reactive timeout in ReactiveException;
            // the root cause is the waiter's own join timeout, not the leader's.
            assertThatThrownBy(() -> waiterFlight.shared().block(Duration.ofSeconds(3)))
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        }

        @Test
        @DisplayName("should surface the leader's failure to the waiter")
        void shouldSurfaceLeaderFailure() throws Exception {
            Mono<CachedResponse> failingWork = Mono.error(new IllegalStateException("boom"));
            Duration wait = Duration.ofSeconds(2);

            RequestCoalescer.Flight leaderFlight = coalescer.join(KEY_A, failingWork, wait);
            leaderFlight.shared().subscribe();
            Thread.sleep(20);

            RequestCoalescer.Flight waiterFlight = coalescer.join(KEY_A, failingWork, wait);
            assertThatThrownBy(() -> waiterFlight.shared().block(Duration.ofSeconds(2))).hasMessageContaining("boom");
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("should report and clear in-flight flights")
        void shouldReportAndClearFlights() throws Exception {
            RequestCoalescer.Flight flight = coalescer.join(KEY_A,
                    Mono.delay(Duration.ofSeconds(1)).thenReturn(RESPONSE), Duration.ofSeconds(5));
            flight.shared().subscribe();
            Thread.sleep(20);
            assertThat(coalescer.inFlight()).isEqualTo(1);

            coalescer.clear();
            assertThat(coalescer.inFlight()).isZero();
        }
    }
}
