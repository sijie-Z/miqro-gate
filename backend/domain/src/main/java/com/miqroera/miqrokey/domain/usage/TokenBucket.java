package com.miqroera.miqrokey.domain.usage;

import java.util.Objects;

/**
 * Immutable token usage observation (protocol-agnostic).
 *
 * <p>
 * Field semantics follow the detailed design document:
 * </p>
 * <ul>
 * <li>{@code inputTokens} / {@code outputTokens} — primary protocol fields
 * (Anthropic: input/output tokens; OpenAI: prompt/completion tokens).</li>
 * <li>{@code cacheCreationInputTokens} / {@code cacheReadInputTokens} —
 * Anthropic cache breakpoints.</li>
 * <li>{@code promptTokens} / {@code completionTokens} / {@code totalTokens} —
 * OpenAI chat usage (kept verbatim for cross-protocol reconciliation).</li>
 * <li>{@code reasoningTokens} — reasoning/thinking tokens (may overlap with
 * output tokens; kept verbatim, never double-counted by the gateway).</li>
 * </ul>
 *
 * <p>
 * Only counts are retained — never prompt or completion content.
 * </p>
 */
public record TokenBucket(Long inputTokens, Long outputTokens, Long cacheCreationInputTokens, Long cacheReadInputTokens,
        Long promptTokens, Long completionTokens, Long totalTokens, Long reasoningTokens) {

    public static final TokenBucket EMPTY = new TokenBucket(null, null, null, null, null, null, null, null);

    public TokenBucket {
        // nullable-by-design: hit events and coalesced events carry no usage
    }

    public boolean isEmpty() {
        return inputTokens == null && outputTokens == null && cacheCreationInputTokens == null
                && cacheReadInputTokens == null && promptTokens == null && completionTokens == null
                && totalTokens == null && reasoningTokens == null;
    }

    /**
     * Overlays a later observation of the <em>same</em> response on top of this
     * one: every non-null field of {@code other} supersedes the field on the
     * left, nulls leave it untouched.
     *
     * <p>
     * Successive provider usage frames must never be summed. Provider counters
     * are cumulative within one response: Anthropic repeats the same
     * {@code input_tokens} (and cache counters) in {@code message_start} and
     * {@code message_delta}, and OpenAI-family final chunks restate the running
     * totals. Adding them double-counts every field the provider reports more
     * than once — the last frame is the authoritative total.
     * </p>
     */
    public TokenBucket overlay(TokenBucket other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        return new TokenBucket(coalesce(inputTokens, other.inputTokens), coalesce(outputTokens, other.outputTokens),
                coalesce(cacheCreationInputTokens, other.cacheCreationInputTokens),
                coalesce(cacheReadInputTokens, other.cacheReadInputTokens), coalesce(promptTokens, other.promptTokens),
                coalesce(completionTokens, other.completionTokens), coalesce(totalTokens, other.totalTokens),
                coalesce(reasoningTokens, other.reasoningTokens));
    }

    /** Later non-null value wins; {@code earlier} is kept when {@code later} is null. */
    private static Long coalesce(Long earlier, Long later) {
        return later != null ? later : earlier;
    }

    @Override
    public String toString() {
        return "TokenBucket[input=" + inputTokens + ", output=" + outputTokens + ", cacheCreation="
                + cacheCreationInputTokens + ", cacheRead=" + cacheReadInputTokens + ", prompt=" + promptTokens
                + ", completion=" + completionTokens + ", total=" + totalTokens + ", reasoning=" + reasoningTokens
                + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof TokenBucket that))
            return false;
        return Objects.equals(inputTokens, that.inputTokens) && Objects.equals(outputTokens, that.outputTokens)
                && Objects.equals(cacheCreationInputTokens, that.cacheCreationInputTokens)
                && Objects.equals(cacheReadInputTokens, that.cacheReadInputTokens)
                && Objects.equals(promptTokens, that.promptTokens)
                && Objects.equals(completionTokens, that.completionTokens)
                && Objects.equals(totalTokens, that.totalTokens)
                && Objects.equals(reasoningTokens, that.reasoningTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, promptTokens,
                completionTokens, totalTokens, reasoningTokens);
    }
}
