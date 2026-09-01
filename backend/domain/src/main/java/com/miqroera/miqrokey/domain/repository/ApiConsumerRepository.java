package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.ApiConsumer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ApiConsumer} (ADR-0010 external-system API channel).
 */
public interface ApiConsumerRepository {

    ApiConsumer insert(ApiConsumer consumer);

    List<ApiConsumer> findAllByTenantId(UUID tenantId);

    Optional<ApiConsumer> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Digest lookup for the auth filter (whole-tenant match is implicit). */
    Optional<ApiConsumer> findByKeyDigest(byte[] keyDigest);

    ApiConsumer update(ApiConsumer consumer);
}
