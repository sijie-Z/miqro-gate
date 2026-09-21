package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.domain.model.McpCircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the LLM-side breaker registry (#741): default-off means
 * zero behavior (no buckets, always allowed), tripping is per (product ×
 * credential), transport errors and configured statuses classify as failures,
 * and the half-open probe closes the breaker on success.
 */
@DisplayName("LlmCircuitBreakerRegistry (#741)")
class LlmCircuitBreakerRegistryTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID CREDENTIAL_A = UUID.randomUUID();
    private static final UUID CREDENTIAL_B = UUID.randomUUID();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));

    private static LlmCircuitBreakerProperties props(boolean enabled, int minRequests, int openSeconds, int probeCount,
            int probeSuccess) {
        return new LlmCircuitBreakerProperties(enabled, null, minRequests, null, null, null, null, null, openSeconds,
                probeCount, probeSuccess);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("disabled: always allowed, no buckets, failures never accumulate")
    void disabledNeverTrips() {
        LlmCircuitBreakerRegistry registry = new LlmCircuitBreakerRegistry(props(false, 1, 1, 1, 1), clock,
                new SimpleMeterRegistry());

        for (int i = 0; i < 5; i++) {
            assertThat(registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_" + i))
                    .isEqualTo(McpCircuitBreaker.Decision.ALLOWED);
            registry.afterCall(PRODUCT, CREDENTIAL_A, 500, false, 10);
        }
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("trips after the configured failures and fails fast per (product × credential)")
    void tripsAndIsolatesBuckets() {
        LlmCircuitBreakerRegistry registry = new LlmCircuitBreakerRegistry(props(true, 2, 30, 3, 2), clock,
                new SimpleMeterRegistry());

        // Two error-status outcomes cross min-requests=2 with a 100% ratio.
        registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_1");
        registry.afterCall(PRODUCT, CREDENTIAL_A, 500, false, 10);
        registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_2");
        registry.afterCall(PRODUCT, CREDENTIAL_A, 0, true, 10); // transport error counts too

        assertThat(registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_3")).isEqualTo(McpCircuitBreaker.Decision.REJECTED);
        // A sibling credential of the same product has its own bucket.
        assertThat(registry.beforeCall(PRODUCT, CREDENTIAL_B, "req_4")).isEqualTo(McpCircuitBreaker.Decision.ALLOWED);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("half-open probe success closes the breaker; failures keep it open")
    void probeRecovers() {
        LlmCircuitBreakerRegistry registry = new LlmCircuitBreakerRegistry(props(true, 1, 30, 1, 1), clock,
                new SimpleMeterRegistry());

        registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_1");
        registry.afterCall(PRODUCT, CREDENTIAL_A, 503, false, 10);
        assertThat(registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_2")).isEqualTo(McpCircuitBreaker.Decision.REJECTED);

        clock.advance(Duration.ofSeconds(31));
        assertThat(registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_3"))
                .isEqualTo(McpCircuitBreaker.Decision.PROBE_ALLOWED);
        registry.afterCall(PRODUCT, CREDENTIAL_A, 200, false, 10); // probe succeeded -> closes

        assertThat(registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_4")).isEqualTo(McpCircuitBreaker.Decision.ALLOWED);
    }

    @Test
    @DisplayName("a 404 (not in the configured error set) is a success and never trips alone")
    void nonErrorStatusIsNotAFailure() {
        LlmCircuitBreakerRegistry registry = new LlmCircuitBreakerRegistry(props(true, 1, 30, 1, 1), clock,
                new SimpleMeterRegistry());

        registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_1");
        registry.afterCall(PRODUCT, CREDENTIAL_A, 404, false, 10);

        assertThat(registry.beforeCall(PRODUCT, CREDENTIAL_A, "req_2")).isEqualTo(McpCircuitBreaker.Decision.ALLOWED);
    }
}
