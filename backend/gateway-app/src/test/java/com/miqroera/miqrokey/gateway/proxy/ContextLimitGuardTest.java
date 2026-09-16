package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the context-limit pre-check (#553): the character measure,
 * the threshold boundary, the kill switch and the metric's zero-label contract.
 */
@DisplayName("ContextLimitGuard")
class ContextLimitGuardTest {

    private static ContextLimitGuard guard(SimpleMeterRegistry registry, boolean enabled, int threshold) {
        return new ContextLimitGuard(new ContextLimitProperties(enabled, threshold), registry);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Character measure")
    class Characters {

        @Test
        @DisplayName("counts ASCII one per byte")
        void countsAscii() {
            assertThat(ContextLimitGuard.characters(utf8("{\"a\":1}"))).isEqualTo(7);
        }

        @Test
        @DisplayName("counts code points, not bytes, for multi-byte UTF-8")
        void countsCodePoints() {
            // 你好世界 = 4 code points / 12 bytes; a single emoji = 1 code point / 4 bytes.
            assertThat(ContextLimitGuard.characters(utf8("你好世界"))).isEqualTo(4);
            assertThat(utf8("你好世界")).hasSize(12);
            assertThat(ContextLimitGuard.characters(utf8("🌍"))).isEqualTo(1);
            assertThat(utf8("🌍")).hasSize(4);
        }

        @Test
        @DisplayName("is zero for an empty body")
        void emptyBody() {
            assertThat(ContextLimitGuard.characters(new byte[0])).isZero();
        }
    }

    @Nested
    @DisplayName("Threshold")
    class Threshold {

        @Test
        @DisplayName("accepts a body exactly at the limit and rejects one character over")
        void boundaryIsInclusive() {
            ContextLimitGuard guard = guard(new SimpleMeterRegistry(), true, 100);
            assertThat(guard.check(utf8("x".repeat(100)), "/v1/messages", "req_1")).isNull();

            AuthFailureException rejected = guard.check(utf8("x".repeat(101)), "/v1/messages", "req_2");
            assertThat(rejected).isNotNull();
            assertThat(rejected.status()).isEqualTo(413);
            assertThat(rejected.code()).isEqualTo("context_limit_exceeded");
            // The message carries sizes only — never request content.
            assertThat(rejected.getMessage()).contains("101").contains("100");
        }

        @Test
        @DisplayName("accepts a multi-byte body whose character count is under the limit")
        void measuresCharactersNotBytes() {
            // 60 characters, 180 bytes: a byte-based guard would reject this.
            ContextLimitGuard guard = guard(new SimpleMeterRegistry(), true, 100);
            assertThat(guard.check(utf8("你".repeat(60)), "/v1/messages", "req_3")).isNull();
        }
    }

    @Nested
    @DisplayName("Kill switch and metric")
    class SwitchAndMetric {

        @Test
        @DisplayName("passes oversized bodies through when disabled")
        void disabledPassesEverything() {
            ContextLimitGuard guard = guard(new SimpleMeterRegistry(), false, 10);
            assertThat(guard.check(utf8("x".repeat(1000)), "/v1/messages", "req_4")).isNull();
        }

        @Test
        @DisplayName("increments a zero-label counter on rejection")
        void countsRejectionsWithoutLabels() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ContextLimitGuard guard = guard(registry, true, 10);

            assertThat(guard.check(utf8("x".repeat(11)), "/v1/messages", "req_5")).isNotNull();
            assertThat(guard.check(utf8("x".repeat(12)), "/v1/messages", "req_6")).isNotNull();
            // An accepted request must not move the counter.
            assertThat(guard.check(utf8("x"), "/v1/messages", "req_7")).isNull();

            var counter = registry.get("miqrokey_gateway_context_limit_rejected_total").counter();
            assertThat(counter.count()).isEqualTo(2.0);
            assertThat(counter.getId().getTags()).isEmpty();
        }

        @Test
        @DisplayName("a disabled guard still registers the metric but never increments it")
        void disabledNeverCounts() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            guard(registry, false, 10).check(utf8("x".repeat(100)), "/v1/messages", "req_8");
            assertThat(registry.get("miqrokey_gateway_context_limit_rejected_total").counter().count()).isZero();
        }
    }

    @Nested
    @DisplayName("Properties defaults")
    class Defaults {

        @Test
        @DisplayName("enabled by default with a generous threshold")
        void defaultsArePermissive() {
            ContextLimitProperties defaults = new ContextLimitProperties(null, null);
            assertThat(defaults.enabled()).isTrue();
            assertThat(defaults.thresholdChars()).isEqualTo(200_000);
        }

        @Test
        @DisplayName("a non-positive threshold falls back to the default")
        void nonPositiveThresholdFallsBack() {
            assertThat(new ContextLimitProperties(true, 0).thresholdChars()).isEqualTo(200_000);
            assertThat(new ContextLimitProperties(true, -1).thresholdChars()).isEqualTo(200_000);
        }
    }
}
