package com.miqroera.miqrokey.domain.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Request completion fact: finalizes the lifecycle record started by
 * {@link RequestStartedEvent} exactly once. The writer applies it with a
 * guarded upsert — {@code UPDATE ... WHERE request_status = 'IN_FLIGHT'} — so a
 * retried flush (idempotent {@code ON CONFLICT} on
 * {@code (started_at, gateway_request_id)}) never double-finalizes and a
 * finalized record's business fields are never rewritten.
 *
 * <p>
 * Cancelled/interrupted requests are finalized too (the client cancelled or the
 * stream failed) — unlike usage events, which are only emitted for fully
 * completed requests. {@code startedAt} must equal the start event's instant
 * (partition key).
 * </p>
 *
 * <p>
 * The event carries the full start snapshot (identity chain, protocol, model):
 * the writer inserts a standalone row when the {@code IN_FLIGHT} start row was
 * never persisted (e.g. the gateway restarted between start and completion, or
 * a failed flush re-enqueued events past a completion), so a completion never
 * depends on a row that might be missing.
 * </p>
 *
 * <p>
 * Never carries prompt, code, tool payloads, or model content.
 * </p>
 */
public record RequestCompletedEvent(UUID id, Instant startedAt, String gatewayRequestId, UUID tenantId, UUID userId,
        UUID projectId, UUID virtualKeyId, UUID providerId, UUID providerProductId, UUID credentialId,
        String wireProtocol, String modelId, boolean streaming, String upstreamRequestId, Instant firstByteAt,
        Instant completedAt, Long durationMs, Long timeToFirstByteMs, Integer httpStatus, RequestStatus status,
        boolean clientCancelled, boolean partialResponse, TokenBucket tokens, boolean usageMissing, int retryCount) {

    @Override
    public String toString() {
        return "RequestCompletedEvent[gatewayRequestId=" + gatewayRequestId + ", status=" + status + ", httpStatus="
                + httpStatus + ", clientCancelled=" + clientCancelled + ", retryCount=" + retryCount + "]";
    }
}
