package com.miqroera.miqrokey.domain.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Request start fact: the gateway authorized a request, decided to forward it
 * upstream, and opened the lifecycle record ({@code request_usage_records},
 * status {@code IN_FLIGHT}). Written through the bounded event bus by the
 * gateway — never synchronously on the hot path.
 *
 * <p>
 * The row is correlated with its completion by
 * {@code (startedAt, gatewayRequestId)}; {@code startedAt} is the partition key
 * of the monthly range-partitioned table, so the completion event must carry
 * the exact same instant.
 * </p>
 *
 * <p>
 * Never carries prompt, code, tool payloads, or model content.
 * </p>
 */
public record RequestStartedEvent(UUID id, Instant startedAt, String gatewayRequestId, UUID tenantId, UUID userId,
        UUID projectId, UUID virtualKeyId, UUID providerId, UUID providerProductId, UUID credentialId,
        String wireProtocol, String modelId, boolean streaming) {

    @Override
    public String toString() {
        return "RequestStartedEvent[gatewayRequestId=" + gatewayRequestId + ", protocol=" + wireProtocol + ", model="
                + modelId + ", streaming=" + streaming + "]";
    }
}
