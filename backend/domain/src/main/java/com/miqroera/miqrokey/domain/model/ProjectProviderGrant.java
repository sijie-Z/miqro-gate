package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A grant authorizing a project to use a specific provider product through a
 * specific upstream credential. Administrators create these; regular users
 * cannot expand authorization beyond what is granted.
 */
public record ProjectProviderGrant(UUID id, UUID projectId, UUID providerProductId, UUID upstreamCredentialId,
        String status, UUID createdBy, long version, Instant createdAt, Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_EXPIRED = "EXPIRED";
}
