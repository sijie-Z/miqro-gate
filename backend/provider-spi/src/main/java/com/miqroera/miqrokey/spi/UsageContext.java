package com.miqroera.miqrokey.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * Correlation context handed to {@link UsageObserver} instances so usage
 * records can be attributed to the request, key and credential version that
 * produced them. Contains no request body and no secrets.
 *
 * @param tenantId
 *            tenant the request belonged to
 * @param virtualKeyId
 *            virtual key used
 * @param projectId
 *            project the key is bound to
 * @param providerProductId
 *            provider product serving the request
 * @param upstreamCredentialVersionId
 *            credential version that authenticated the upstream call
 * @param gatewayRequestId
 *            gateway request id
 * @param providerRequestId
 *            provider request id, when known (may be {@code null})
 * @param protocol
 *            protocol family of the request
 * @param startedAt
 *            request start time
 */
public record UsageContext(UUID tenantId, UUID virtualKeyId, UUID projectId, UUID providerProductId,
        UUID upstreamCredentialVersionId, String gatewayRequestId, String providerRequestId, ProtocolFamily protocol,
        Instant startedAt) {

    public UsageContext {
        if (tenantId == null || virtualKeyId == null || projectId == null || providerProductId == null
                || upstreamCredentialVersionId == null || gatewayRequestId == null || gatewayRequestId.isBlank()
                || protocol == null || startedAt == null) {
            throw new IllegalArgumentException("all attribution fields are required");
        }
    }
}
