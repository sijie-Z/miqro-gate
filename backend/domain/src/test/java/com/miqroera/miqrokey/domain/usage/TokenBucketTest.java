package com.miqroera.miqrokey.domain.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the protocol-agnostic usage observation bucket.
 */
@DisplayName("TokenBucket")
class TokenBucketTest {

    @Nested
    @DisplayName("Emptiness")
    class Emptiness {

        @Test
        @DisplayName("should be empty when no count is set")
        void shouldBeEmptyByDefault() {
            assertThat(TokenBucket.EMPTY.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("should be non-empty when any count is set")
        void shouldBeNonEmptyWhenAnyCountSet() {
            assertThat(new TokenBucket(1L, null, null, null, null, null, null, null).isEmpty()).isFalse();
            assertThat(new TokenBucket(null, null, null, null, null, null, 1L, null).isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("Overlaying")
    class Overlaying {

        @Test
        @DisplayName("should let the later observation supersede every non-null field")
        void shouldSupersedeWithLaterValues() {
            // Provider counters are cumulative within one response (Anthropic
            // repeats input_tokens in message_start and message_delta); the last
            // frame is the total, so the two must never be added together.
            TokenBucket start = new TokenBucket(10L, 0L, 0L, 0L, 10L, 0L, 10L, 0L);
            TokenBucket delta = new TokenBucket(10L, 8L, 150L, 300L, 10L, 8L, 18L, 4L);
            TokenBucket latest = start.overlay(delta);
            assertThat(latest.inputTokens()).isEqualTo(10L);
            assertThat(latest.outputTokens()).isEqualTo(8L);
            assertThat(latest.cacheCreationInputTokens()).isEqualTo(150L);
            assertThat(latest.cacheReadInputTokens()).isEqualTo(300L);
            assertThat(latest.promptTokens()).isEqualTo(10L);
            assertThat(latest.completionTokens()).isEqualTo(8L);
            assertThat(latest.totalTokens()).isEqualTo(18L);
            assertThat(latest.reasoningTokens()).isEqualTo(4L);
        }

        @Test
        @DisplayName("should keep earlier fields the later observation does not carry")
        void shouldKeepFieldsAbsentFromLaterObservation() {
            TokenBucket partial = new TokenBucket(10L, null, null, null, null, null, null, null);
            TokenBucket other = new TokenBucket(null, 7L, null, null, null, null, null, null);
            TokenBucket latest = partial.overlay(other);
            assertThat(latest.inputTokens()).isEqualTo(10L);
            assertThat(latest.outputTokens()).isEqualTo(7L);
        }

        @Test
        @DisplayName("should be the identity for an empty other bucket")
        void shouldBeIdentityForEmpty() {
            TokenBucket a = new TokenBucket(10L, 5L, null, null, null, null, null, null);
            assertThat(a.overlay(TokenBucket.EMPTY)).isSameAs(a);
            assertThat(a.overlay(null)).isSameAs(a);
        }

        @Test
        @DisplayName("should not mutate the operands")
        void shouldNotMutateOperands() {
            TokenBucket a = new TokenBucket(1L, null, null, null, null, null, null, null);
            TokenBucket b = new TokenBucket(2L, null, null, null, null, null, null, null);
            a.overlay(b);
            assertThat(a.inputTokens()).isEqualTo(1L);
            assertThat(b.inputTokens()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("should compare by content")
        void shouldCompareByContent() {
            TokenBucket a = new TokenBucket(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
            TokenBucket b = new TokenBucket(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("should never expose content in toString")
        void shouldNotExposeContentInToString() {
            // The bucket only holds counts — nothing sensitive, but toString
            // must stay purely numeric.
            assertThat(new TokenBucket(1L, 2L, null, null, null, null, 3L, null).toString()).contains("input=1")
                    .contains("output=2").contains("total=3");
        }
    }
}
