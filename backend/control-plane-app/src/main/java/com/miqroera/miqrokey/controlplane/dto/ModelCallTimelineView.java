package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One model call's lifecycle timeline (#705) — the model-side counterpart of
 * the MCP call timeline delivered in #689.
 *
 * <p>
 * Composed from {@code request_usage_records}, which already carries every
 * timestamp the timeline needs ({@code started_at}, {@code first_byte_at},
 * {@code completed_at}, {@code time_to_first_byte_ms}, {@code duration_ms}) —
 * no new collection is involved.
 * </p>
 *
 * @param status
 *            lifecycle terminal; {@code IN_FLIGHT} means the record was never
 *            finalized (a stale row after a gateway restart, or a call still
 *            running) — surfaced as-is rather than hidden
 * @param phases
 *            the ordered milestones actually observed for this call; a
 *            cancelled or timed-out call simply has fewer phases
 */
public record ModelCallTimelineView(String gatewayRequestId, String upstreamRequestId, String modelId,
        String wireProtocol, boolean streaming, String status, Integer httpStatus, boolean clientCancelled,
        boolean partialResponse, int retryCount, Instant startedAt, Instant firstByteAt, Instant completedAt,
        Long durationMs, Long timeToFirstByteMs, Tokens tokens, Attribution attribution, List<Phase> phases) {

    /**
     * Token counts of the call; any field may be null when upstream omitted usage.
     */
    public record Tokens(Long input, Long output, Long cacheRead, Long cacheCreation) {
    }

    /**
     * Attribution chain of the call (who / which project / through which key and
     * credential).
     */
    public record Attribution(UUID userId, UUID projectId, UUID virtualKeyId, UUID providerId, UUID providerProductId,
            UUID credentialId) {
    }

    /**
     * One milestone on the timeline.
     *
     * @param key
     *            stable machine key: {@code ACCEPTED}, {@code FIRST_BYTE} or
     *            {@code COMPLETED}
     * @param elapsedMs
     *            milliseconds since {@code startedAt}; {@code 0} for the first
     *            phase
     */
    public record Phase(String key, String label, Instant at, Long elapsedMs) {
    }
}
