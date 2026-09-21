package com.miqroera.miqrokey.domain.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of one forwarded model call — a row of
 * {@code request_usage_records} (the per-request lifecycle audit trail written
 * by the gateway as {@code IN_FLIGHT} and finalized exactly once).
 *
 * <p>
 * Used by the model-call timeline view (#705), which replays a single call's
 * phases by {@code gatewayRequestId}. Only counts and metadata are carried —
 * never prompt, code or model content.
 * </p>
 *
 * @param requestStatus
 *            one of the lifecycle terminals defined by {@link RequestStatus};
 *            {@code IN_FLIGHT} means the record has not been finalized yet
 *            (stale in-flight rows are possible after a gateway restart and are
 *            surfaced as-is rather than hidden).
 */
public record ModelCallRecord(UUID id, String gatewayRequestId, String upstreamRequestId, UUID tenantId, UUID userId,
        UUID projectId, UUID virtualKeyId, UUID providerId, UUID providerProductId, UUID credentialId, String modelId,
        String wireProtocol, boolean streaming, String requestStatus, Instant startedAt, Instant firstByteAt,
        Instant completedAt, Long durationMs, Long timeToFirstByteMs, Integer httpStatus, boolean clientCancelled,
        boolean partialResponse, int retryCount, Long inputTokens, Long outputTokens, Long cacheReadInputTokens,
        Long cacheCreationInputTokens) {
}
