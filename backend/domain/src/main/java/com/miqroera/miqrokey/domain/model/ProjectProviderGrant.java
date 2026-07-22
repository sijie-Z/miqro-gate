package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A grant authorizing a project to use a specific provider product through a
 * specific upstream credential. Administrators create these; regular users
 * cannot expand authorization beyond what is granted.
 */
public record ProjectProviderGrant(UUID id, UUID tenantId, UUID projectId, UUID providerProductId,
        UUID upstreamCredentialId, GrantStatus status, UUID createdBy, long version, Instant createdAt,
        Instant updatedAt) {
}
