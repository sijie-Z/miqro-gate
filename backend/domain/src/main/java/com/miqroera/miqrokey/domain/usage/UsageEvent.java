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
 * Never carries prompt, code, tool payloads, or model content. {@code clientIp}
 * is the calling-party network address (transport peer, or the rightmost
 * non-trusted X-Forwarded-For hop behind a configured trusted proxy) — recorded
 * for abuse forensics, nullable when unresolvable.
 * </p>
 */
public record UsageEvent(UUID id, UUID tenantId, String providerRequestId, UUID virtualKeyId, UUID projectId,
        UUID providerProductId, UUID credentialId, String modelId, CacheLevel cacheLevel, TokenBucket tokens,
        Long latencyMs, Integer upstreamStatusCode, byte[] cacheKey, boolean isComplete, boolean usageMissing,
        String gatewayRequestId, Instant occurredAt, String clientIp, ContextAttribution attribution) {

    /**
     * CAA per-request attribution metadata (Spec v1.1 §7.1): the client's CLAIMS
     * kept for audit plus the server's resolution status. Null when the request
     * carried no context information.
     *
     * <p>
     * {@code resolutionCandidates} is the number of ACTIVE bindings the key held
     * when the ladder ran (#1139): 1 means there was nothing to choose from — the
     * suffix merely matched it — and &gt;1 means the ruling really selected among
     * candidates. Null only for rows written before V72 (and for legacy fixture
     * shapes): unknown, never guessed. A count, deliberately not the binding
     * details.
     * </p>
     *
     * <p>
     * {@code bindingTag} is the resolved binding's project tag. It has no
     * {@code usage_event} column: it is carried here for the audit evidence row
     * ({@code request_context_evidence}, Spec v1.1 §7.2), which records it as the
     * {@code suffix} evidence value when {@code resolutionStatus} is
     * {@code RESOLVED_SUFFIX} — the binding index is keyed by project tag, so for
     * that status it is exactly the tag the client presented in the key. Null for
     * the policy-synthesized binding.
     * </p>
     */
    public record ContextAttribution(String sessionId, UUID activityId, UUID claimedProjectId, String resolutionStatus,
            Integer resolutionCandidates, String claimSource, String claimConfidence, String bindingTag) {
    }

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
