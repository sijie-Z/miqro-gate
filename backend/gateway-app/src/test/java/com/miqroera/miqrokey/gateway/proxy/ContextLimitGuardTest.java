package com.miqroera.miqrokey.gateway.proxy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the context-limit pre-check (#553): the character measure,
 * the threshold boundary, the kill switch, the log line and the metric's
 * zero-label contract.
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

        @Test
        @DisplayName("measures malformed UTF-8 in bytes instead of under-counting it")
        void malformedBodiesFallBackToByteLength() {
            byte[] stray = new byte[250];
            Arrays.fill(stray, (byte) 0x80);
            // The measure used to skip every continuation byte and report zero for
            // this body while a lenient decoder still produced 250 characters.
            assertThat(new String(stray, StandardCharsets.UTF_8)).hasSize(250);
            assertThat(ContextLimitGuard.characters(stray)).isEqualTo(250);

            // Well-formed bytes around a malformed run do not rescue the count:
            // the body as a whole is not well-formed UTF-8, so it is measured in
            // bytes — an over-count of the 257 decoded characters here.
            byte[] mixed = new byte[7 + stray.length];
            System.arraycopy(utf8("{\"a\":1}"), 0, mixed, 0, 7);
            System.arraycopy(stray, 0, mixed, 7, stray.length);
            assertThat(ContextLimitGuard.characters(mixed)).isEqualTo(mixed.length);
        }

        @Test
        @DisplayName("rejects the malformed forms a prefix heuristic would under-count")
        void malformedFormsAreNeverUnderCounted() {
            // Each of these ends with a byte that a "count everything that is not
            // 10xxxxxx" scan would consume while a lenient decoder still emits a
            // character for it: overlong C0 AF, surrogate ED A0 80, out-of-range
            // F5 80 80 80, and a truncated E4 BD.
            assertNeverUnderCounts(new byte[]{(byte) 0x80});
            assertNeverUnderCounts(new byte[]{(byte) 0xC0, (byte) 0xAF});
            assertNeverUnderCounts(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80});
            assertNeverUnderCounts(new byte[]{(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80});
            assertNeverUnderCounts(new byte[]{(byte) 0xE4, (byte) 0xBD});
            assertNeverUnderCounts(new byte[]{(byte) 0xF8, (byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80});
        }

        @Test
        @DisplayName("never reports fewer characters than a lenient UTF-8 decode produces")
        void neverUnderCountsDecodedCharacters() {
            // Exhaustive over every one- and two-byte sequence (65792 bodies),
            // then a seeded fuzz of longer ones. This is the property the guard
            // rests on: over-counting can only reject early, under-counting would
            // promise the caller a request the provider cannot serve.
            for (int first = 0; first < 256; first++) {
                assertNeverUnderCounts(new byte[]{(byte) first});
                for (int second = 0; second < 256; second++) {
                    assertNeverUnderCounts(new byte[]{(byte) first, (byte) second});
                }
            }
            Random random = new Random(553);
            for (int i = 0; i < 5_000; i++) {
                byte[] fuzz = new byte[random.nextInt(32)];
                random.nextBytes(fuzz);
                assertNeverUnderCounts(fuzz);
            }
        }

        private static void assertNeverUnderCounts(byte[] body) {
            String decoded = new String(body, StandardCharsets.UTF_8);
            int decodedCodePoints = decoded.codePointCount(0, decoded.length());
            assertThat(ContextLimitGuard.characters(body)).as("measure of %s", Arrays.toString(body))
                    .isGreaterThanOrEqualTo(decodedCodePoints);
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

        @Test
        @DisplayName("the shipped default threshold is inclusive at 200000 characters")
        void defaultThresholdBoundary() {
            // Built through the default path (threshold-chars absent), so this
            // pins the value that ships, not just a fixture: 200000 passes,
            // 200001 is rejected. Contexts of 200001..262144 characters now get
            // a 413 here even though max-proxy-buffer would still forward them.
            ContextLimitGuard guard = new ContextLimitGuard(new ContextLimitProperties(true, null),
                    new SimpleMeterRegistry());
            assertThat(guard.check(utf8("x".repeat(200_000)), "/v1/messages", "req_9")).isNull();

            AuthFailureException rejected = guard.check(utf8("x".repeat(200_001)), "/v1/messages", "req_10");
            assertThat(rejected).isNotNull();
            assertThat(rejected.status()).isEqualTo(413);
            assertThat(rejected.code()).isEqualTo("context_limit_exceeded");
        }

        @Test
        @DisplayName("rejects a 250000-byte all-continuation body at the shipped default threshold")
        void malformedBodyAtTheDefaultThresholdIsRejected() {
            // The regression that motivated the conservative measure: 250000 bytes
            // of 10xxxxxx used to measure zero and pass a 200000 threshold
            // unopposed. 250000 bytes is still under the 256 KiB
            // max-proxy-buffer, so a real client can reach the guard with it.
            byte[] body = new byte[250_000];
            Arrays.fill(body, (byte) 0x80);

            ContextLimitGuard guard = new ContextLimitGuard(new ContextLimitProperties(true, null),
                    new SimpleMeterRegistry());
            AuthFailureException rejected = guard.check(body, "/v1/messages", "req_11");

            assertThat(rejected).isNotNull();
            assertThat(rejected.status()).isEqualTo(413);
            assertThat(rejected.code()).isEqualTo("context_limit_exceeded");
            assertThat(rejected.getMessage()).contains("250000").contains("200000");
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

        @Test
        @DisplayName("logs one WARN line with sizes only, never request content")
        void rejectionLogCarriesNoBodyContent() {
            String sentinel = "SENSITIVE-PROMPT-MARKER-9f13";
            Logger logger = (Logger) LoggerFactory.getLogger(ContextLimitGuard.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            try {
                // The sentinel sits inside the oversized body; if the log line
                // carried the body it would surface here.
                String body = "{\"content\":\"" + sentinel + "\"" + "x".repeat(60) + "}";
                AuthFailureException rejected = guard(new SimpleMeterRegistry(), true, 10).check(utf8(body),
                        "/v1/messages", "req_12");

                assertThat(rejected).isNotNull();
                assertThat(appender.list).hasSize(1);
                ILoggingEvent event = appender.list.get(0);
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("context_limit_exceeded").contains("req_12")
                        .contains("bodyChars=").doesNotContain(sentinel).doesNotContain("SENSITIVE")
                        .doesNotContain(body);
            } finally {
                logger.detachAppender(appender);
            }
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
