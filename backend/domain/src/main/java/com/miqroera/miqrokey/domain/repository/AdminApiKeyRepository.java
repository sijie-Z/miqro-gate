package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.AdminApiKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AdminApiKey} (ADR-0015 open admin API credentials).
 */
public interface AdminApiKeyRepository {

    AdminApiKey insert(AdminApiKey key);

    List<AdminApiKey> findAllByTenantId(UUID tenantId);

    Optional<AdminApiKey> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Digest lookup for the auth filter (whole-tenant match is implicit). */
    Optional<AdminApiKey> findActiveByDigest(byte[] keyDigest);

    /** Marks revoked; returns false when the row is missing or already revoked. */
    boolean revoke(UUID id, UUID tenantId, Instant revokedAt);

    /** Replaces the capability scope (null list = full access). */
    boolean updateScope(UUID id, UUID tenantId, List<String> capabilities);
}
