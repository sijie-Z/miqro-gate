package com.miqroera.miqrokey.controlplane.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One unit-price snapshot (api-contract §5.9): price per one million tokens for
 * a (product, model, token type) triple. Snapshots are immutable; the latest
 * {@code effectiveFrom} before a request applies to its cost.
 */
public record PriceSnapshotView(UUID id, UUID providerProductId, String modelId, String tokenType, String currency,
        BigDecimal unitPrice, Instant effectiveFrom, String source, UUID createdBy, Instant createdAt) {
}
