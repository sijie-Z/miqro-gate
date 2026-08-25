package com.miqroera.miqrokey.spi;

/**
 * One usage record extracted by an adapter. Token counts follow the provider's
 * own accounting (they are stored verbatim, never re-derived), and
 * {@code cacheRead}/{@code cacheCreation} track the provider's prompt-cache
 * semantics separately from gateway-side caching.
 *
 * @param modelId
 *            provider model id
 * @param inputTokens
 *            input tokens (may be {@code null})
 * @param outputTokens
 *            output tokens (may be {@code null})
 * @param cacheReadInputTokens
 *            prompt-cache read tokens (may be {@code null})
 * @param cacheCreationInputTokens
 *            prompt-cache write tokens (may be {@code null})
 * @param providerRequestId
 *            provider request id (may be {@code null})
 * @param finishReason
 *            finish reason from the provider (may be {@code null})
 * @param error
 *            error detail; must be sanitized, never a full body (may be
 *            {@code null})
 * @param source
 *            provenance of this observation
 * @param confidence
 *            parse confidence 0..1 (may be {@code null})
 */
public record UsageObservation(String modelId, Long inputTokens, Long outputTokens, Long cacheReadInputTokens,
        Long cacheCreationInputTokens, String providerRequestId, String finishReason, String error, UsageSource source,
        Double confidence) {

    public UsageObservation {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
    }
}
