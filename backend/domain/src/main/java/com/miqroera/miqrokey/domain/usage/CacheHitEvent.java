package com.miqroera.miqrokey.domain.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * A cache hit fact. Deduplicated per {@code (cache_key, level, second)} via a
 * unique index, so repeated flushes never double-count.
 *
 * <p>
 * Only counts are recorded — never cached response content, prompts, or model
 * output.
 * </p>
 */
public record CacheHitEvent(UUID id, UUID tenantId, byte[] cacheKey, UUID virtualKeyId, UUID projectId,
        UUID providerProductId, CacheLevel level, String gatewayRequestId, Instant occurredAt) {

    public CacheHitEvent {
        if (level != CacheLevel.L1_HIT && level != CacheLevel.L2_HIT) {
            throw new IllegalArgumentException("CacheHitEvent level must be L1_HIT or L2_HIT, got " + level);
        }
        cacheKey = cacheKey.clone();
    }

    @Override
    public byte[] cacheKey() {
        return cacheKey.clone();
    }

    @Override
    public String toString() {
        return "CacheHitEvent[level=" + level + ", cacheKeyPresent=" + (cacheKey != null && cacheKey.length > 0)
                + ", occurredAt=" + occurredAt + "]";
    }
}
