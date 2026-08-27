package com.miqroera.miqrokey.spi;

import java.net.URI;
import java.util.UUID;

/**
 * The validated routing decision for one request: which tenant/product/project
 * the virtual key resolved to, which protocol family was negotiated, and the
 * product's upstream base URL. Produced by the gateway's route snapshot; the
 * adapter's {@code resolve} turns it into a {@link TargetRequest}.
 *
 * @param tenantId
 *            tenant owning the key
 * @param providerProductId
 *            provider product the key is bound to
 * @param projectId
 *            project the key is bound to
 * @param protocol
 *            protocol family the inbound request speaks
 * @param baseUrl
 *            product base URL (signed catalog or admin-chosen allowlisted
 *            value; never user input)
 */
public record RouteContext(UUID tenantId, UUID providerProductId, UUID projectId, ProtocolFamily protocol,
        URI baseUrl) {

    public RouteContext {
        if (tenantId == null || providerProductId == null || projectId == null || protocol == null || baseUrl == null) {
            throw new IllegalArgumentException("all fields are required");
        }
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException("baseUrl must use https, got " + baseUrl);
        }
        if (baseUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must not contain userinfo: " + baseUrl);
        }
    }
}
