package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.RetentionConfig;

import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code retention_config} (V31, ADR-0014): per-tenant compliance
 * switch carried by the route snapshot so a running gateway switches without
 * restart.
 */
public interface RetentionConfigRepository {

    Optional<RetentionConfig> find(UUID tenantId);

    RetentionConfig upsert(UUID tenantId, RetentionConfig config, UUID updatedBy);
}
