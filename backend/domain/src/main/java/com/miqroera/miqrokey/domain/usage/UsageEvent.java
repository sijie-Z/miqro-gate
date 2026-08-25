package com.miqroera.miqrokey.domain.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * One tiered usage fact. Written asynchronously by the gateway through the
 * bounded event bus and persisted by {@code INSERT ... ON CONFLICT DO NOTHING}
 * on {@code (tenant_id, provider_request_id)} for idempotency.
 *
 * <h2>Row semantics by cache level</h2>
 * <ul>
 * <li>{@code UPSTREAM} — full row with observed tokens and the upstream request
 * id (e.g. {@code chatcmpl-...}).</li>
 * <li>{@code COALESCED} — same shape as UPSTREAM (leader's tokens), but
 * {@code providerRequestId} is null so multiple merged rows never
 * conflict.</li>
 * <li>{@code L1_HIT} / {@code L2_HIT} — not written to this table; counted in
 * {@link CacheHitEvent}.</li>
 * </ul>
 *
 * <p>
 * Never carries prompt, code, tool payloads, or model content.
 * </p>
 */
public record UsageEvent(UUID id, UUID tenantId, String providerRequestId, UUID virtualKeyId, UUID projectId,
        UUID providerProductId, UUID credentialId, String modelId, CacheLevel cacheLevel, TokenBucket tokens,
        Long latencyMs, Integer upstreamStatusCode, byte[] cacheKey, boolean isComplete, boolean usageMissing,
        String gatewayRequestId, Instant occurredAt) {

    public UsageEvent {
        cacheKey = cacheKey != null ? cacheKey.clone() : null;
    }

    @Override
    public byte[] cacheKey() {
        return cacheKey != null ? cacheKey.clone() : null;
    }

    /**
     * Does not expose token counts or keys in logs beyond safe metadata.
     */
    @Override
    public String toString() {
        return "UsageEvent[cacheLevel=" + cacheLevel + ", model=" + modelId + ", complete=" + isComplete
                + ", usageMissing=" + usageMissing + ", providerRequestIdPresent=" + (providerRequestId != null) + "]";
    }
}
