package com.miqroera.miqrokey.domain.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Unit price per million tokens for a (product, model, token type) triple.
 *
 * <p>
 * Not tenant-scoped: prices belong to the global provider product catalog. The
 * latest {@code effective_from} at or before an event's occurrence is applied
 * for cost computation (see {@code UsageStatsAggregator}).
 * </p>
 */
public record PriceSnapshot(UUID id, UUID providerProductId, String modelId, PriceTokenType tokenType, String currency,
        BigDecimal unitPrice, Instant effectiveFrom, String source, UUID createdBy, Instant createdAt) {
}
