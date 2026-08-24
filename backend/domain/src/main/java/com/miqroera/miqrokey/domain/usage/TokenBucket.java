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
     * Merges two observations (e.g. multiple SSE frames) by summing non-null
     * fields.
     */
    public TokenBucket merge(TokenBucket other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        return new TokenBucket(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens),
                sum(cacheCreationInputTokens, other.cacheCreationInputTokens),
                sum(cacheReadInputTokens, other.cacheReadInputTokens), sum(promptTokens, other.promptTokens),
                sum(completionTokens, other.completionTokens), sum(totalTokens, other.totalTokens),
                sum(reasoningTokens, other.reasoningTokens));
    }

    private static Long sum(Long a, Long b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return a + b;
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
