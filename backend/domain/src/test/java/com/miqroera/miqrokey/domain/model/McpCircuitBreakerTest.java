package com.miqroera.miqrokey.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * F13 breaker state machine matrix (doc 134859): min-request guard, error and
 * slow-call triggers, open timer, half-open probing and recovery.
 */
@DisplayName("MCP circuit breaker")
class McpCircuitBreakerTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-05T00:00:00Z"));

    private McpResiliencePolicy errorPolicy(int minRequests, int errorRatio) {
        return new McpResiliencePolicy(false, 1, Set.of(), false, true, 60, minRequests, true, errorRatio,
                Set.of(500, 502, 503, 504), false, 3000, 80, 30, 3, 2, true, 0);
    }

    private McpResiliencePolicy slowPolicy(int slowMs, int slowRatio) {
        return new McpResiliencePolicy(false, 1, Set.of(), false, true, 60, 1, false, 50, Set.of(500), true, slowMs,
                slowRatio, 30, 3, 2, true, 0);
    }

    @Test
    @DisplayName("below the min-request guard nothing trips")
    void minRequestGuard() {
        McpCircuitBreaker breaker = new McpCircuitBreaker(errorPolicy(10, 50), clock);
        for (int i = 0; i < 9; i++) {
            assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.ALLOWED);
            breaker.afterCall(false, 10);
        }
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("error ratio crossing opens the breaker after the guard")
    void errorRatioOpens() {
        McpCircuitBreaker breaker = new McpCircuitBreaker(errorPolicy(4, 50), clock);
        breaker.afterCall(true, 10);
        breaker.afterCall(true, 10);
        breaker.afterCall(true, 10);
        breaker.afterCall(false, 10); // 1/4 = 25% — still closed
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
        breaker.afterCall(false, 10); // 2/5 = 40% < 50
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
        breaker.afterCall(false, 10); // 3/6 = 50% ≥ 50
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.OPEN);
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.REJECTED);
    }

    @Test
    @DisplayName("configured status-code set decides the caller's ok flag only")
    void errorClassificationIsCallerOwned() {
        // The domain never inspects HTTP codes: the caller classifies and the
        // breaker only tallies ok/fail + duration. A policy with an empty code
        // set is legal and never trips on errors.
        McpResiliencePolicy noCodes = new McpResiliencePolicy(false, 1, Set.of(), false, true, 60, 1, true, 50,
                Set.of(), false, 3000, 80, 30, 3, 2, true, 0);
        McpCircuitBreaker breaker = new McpCircuitBreaker(noCodes, clock);
        breaker.afterCall(false, 10);
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.OPEN); // 1/1 = 100% errors
    }

    @Test
    @DisplayName("window slides: old samples expire")
    void windowSlides() {
        McpCircuitBreaker breaker = new McpCircuitBreaker(errorPolicy(3, 100), clock);
        breaker.afterCall(false, 10);
        breaker.afterCall(false, 10);
        clock.advance(Duration.ofSeconds(61));
        breaker.afterCall(true, 10); // only 1 sample in the new window
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("slow-call trigger opens when enabled; the boundary is strict >")
    void slowTriggerOpens() {
        McpCircuitBreaker strict = new McpCircuitBreaker(slowPolicy(100, 80), clock);
        strict.afterCall(true, 100); // not > slowMs → not slow (0/1)
        assertThat(strict.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
        strict.afterCall(true, 120); // 1/2 = 50% < 80
        assertThat(strict.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
        strict.afterCall(true, 200); // 2/3 = 66% < 80
        assertThat(strict.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
        strict.afterCall(true, 300); // 3/4 = 75% < 80
        assertThat(strict.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
        strict.afterCall(true, 400); // 4/5 = 80% ≥ 80 → open
        assertThat(strict.state()).isEqualTo(McpCircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("open expires into half-open probes and recovers on success")
    void halfOpenRecovers() {
        McpCircuitBreaker breaker = new McpCircuitBreaker(errorPolicy(1, 50), clock);
        breaker.afterCall(false, 10);
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.PROBE_ALLOWED);
        breaker.afterCall(true, 10);
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.PROBE_ALLOWED);
        breaker.afterCall(true, 10); // 2 successes ≥ probeSuccess → closed
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.CLOSED);
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.ALLOWED);
    }

    @Test
    @DisplayName("a half-open probe failure re-opens the breaker")
    void halfOpenFailureReopens() {
        McpCircuitBreaker breaker = new McpCircuitBreaker(errorPolicy(1, 50), clock);
        breaker.afterCall(false, 10);
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.PROBE_ALLOWED);
        breaker.afterCall(false, 10);
        assertThat(breaker.state()).isEqualTo(McpCircuitBreaker.State.OPEN);
        // Probes are capped by probeCount while half-open.
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.PROBE_ALLOWED);
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.PROBE_ALLOWED);
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.PROBE_ALLOWED);
        assertThat(breaker.beforeCall()).isEqualTo(McpCircuitBreaker.Decision.REJECTED);
    }

    @Test
    @DisplayName("guard clauses: must be enabled and carry at least one trigger")
    void guards() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new McpCircuitBreaker(McpResiliencePolicy.disabled(), clock));
        McpResiliencePolicy noTrigger = new McpResiliencePolicy(false, 1, Set.of(), false, true, 10, 10, false, 50,
                Set.of(500), false, 3000, 80, 30, 3, 2, true, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> new McpCircuitBreaker(noTrigger, clock));
    }

    /** Test clock with a mutable instant. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
