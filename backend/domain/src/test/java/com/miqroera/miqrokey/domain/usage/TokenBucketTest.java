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
    @DisplayName("Merging")
    class Merging {

        @Test
        @DisplayName("should sum all non-null fields")
        void shouldSumNonNullFields() {
            TokenBucket a = new TokenBucket(10L, 5L, 2L, 3L, 10L, 5L, 15L, 1L);
            TokenBucket b = new TokenBucket(20L, 1L, 1L, 1L, 20L, 1L, 21L, 2L);
            TokenBucket merged = a.merge(b);
            assertThat(merged.inputTokens()).isEqualTo(30L);
            assertThat(merged.outputTokens()).isEqualTo(6L);
            assertThat(merged.cacheCreationInputTokens()).isEqualTo(3L);
            assertThat(merged.cacheReadInputTokens()).isEqualTo(4L);
            assertThat(merged.promptTokens()).isEqualTo(30L);
            assertThat(merged.completionTokens()).isEqualTo(6L);
            assertThat(merged.totalTokens()).isEqualTo(36L);
            assertThat(merged.reasoningTokens()).isEqualTo(3L);
        }

        @Test
        @DisplayName("should fill nulls from the other side")
        void shouldFillNulls() {
            TokenBucket partial = new TokenBucket(10L, null, null, null, null, null, null, null);
            TokenBucket other = new TokenBucket(null, 7L, null, null, null, null, null, null);
            TokenBucket merged = partial.merge(other);
            assertThat(merged.inputTokens()).isEqualTo(10L);
            assertThat(merged.outputTokens()).isEqualTo(7L);
        }

        @Test
        @DisplayName("should be the identity for an empty other bucket")
        void shouldBeIdentityForEmpty() {
            TokenBucket a = new TokenBucket(10L, 5L, null, null, null, null, null, null);
            assertThat(a.merge(TokenBucket.EMPTY)).isSameAs(a);
            assertThat(a.merge(null)).isSameAs(a);
        }

        @Test
        @DisplayName("should not mutate the operands")
        void shouldNotMutateOperands() {
            TokenBucket a = new TokenBucket(1L, null, null, null, null, null, null, null);
            TokenBucket b = new TokenBucket(2L, null, null, null, null, null, null, null);
            a.merge(b);
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
