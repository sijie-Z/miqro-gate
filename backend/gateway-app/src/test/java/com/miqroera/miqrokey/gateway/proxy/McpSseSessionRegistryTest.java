package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inbound SSE session registry (#356): capacity bound, idle sweeping and touch
 * semantics, on an injectable clock.
 */
@DisplayName("McpSseSessionRegistry")
class McpSseSessionRegistryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-10T10:00:00Z"));

    @Test
    @DisplayName("opens sessions bound to consumer and service")
    void opensBoundSessions() {
        McpSseSessionRegistry registry = new McpSseSessionRegistry(clock);

        McpSseSessionRegistry.SseSession session = registry.tryOpen(UUID.randomUUID(), UUID.randomUUID(), "open-demo")
                .orElseThrow();

        assertThat(registry.find(session.id())).contains(session);
        assertThat(session.serviceName()).isEqualTo("open-demo");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses beyond the capacity bound")
    void refusesAtCapacity() {
        McpSseSessionRegistry registry = new McpSseSessionRegistry(clock);
        for (int i = 0; i < McpSseSessionRegistry.MAX_SESSIONS; i++) {
            assertThat(registry.tryOpen(UUID.randomUUID(), UUID.randomUUID(), "open-demo")).isPresent();
        }

        assertThat(registry.tryOpen(UUID.randomUUID(), UUID.randomUUID(), "open-demo")).isEmpty();
        assertThat(registry.size()).isEqualTo(McpSseSessionRegistry.MAX_SESSIONS);
    }

    @Test
    @DisplayName("the idle sweep ends sessions past the TTL; touch resets it")
    void idleSweepHonoursTouch() {
        McpSseSessionRegistry registry = new McpSseSessionRegistry(clock);
        McpSseSessionRegistry.SseSession kept = registry.tryOpen(UUID.randomUUID(), UUID.randomUUID(), "a")
                .orElseThrow();
        McpSseSessionRegistry.SseSession idle = registry.tryOpen(UUID.randomUUID(), UUID.randomUUID(), "b")
                .orElseThrow();

        clock.advance(Duration.ofMinutes(4));
        registry.touch(kept); // traffic before the TTL fires
        clock.advance(Duration.ofMinutes(4));
        registry.reapIdle();

        assertThat(registry.find(kept.id())).contains(kept);
        assertThat(registry.find(idle.id())).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("close removes the session and unknown ids are inert")
    void closeRemoves() {
        McpSseSessionRegistry registry = new McpSseSessionRegistry(clock);
        UUID id = registry.tryOpen(UUID.randomUUID(), UUID.randomUUID(), "a").orElseThrow().id();

        registry.close(id);
        registry.close(id); // idempotent

        assertThat(registry.find(id)).isEmpty();
        assertThat(registry.find(null)).isEqualTo(Optional.empty());
    }

    /** Test-only clock with controllable time. */
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
}
