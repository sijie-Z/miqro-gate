package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.QuotaDefaultTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code quota_default_template} (V26): the per-tenant global default
 * quota strategy. A row exists only after an admin configured a definition —
 * before that the tenant has no default strategy.
 */
public interface QuotaDefaultTemplateRepository {

    Optional<QuotaDefaultTemplate> find(UUID tenantId);

    /** Insert or update the definition; bumps version on update. */
    QuotaDefaultTemplate upsertDefinition(QuotaDefaultTemplate template);

    /** Flips {@code enabled}; caller must have ensured a row exists. */
    QuotaDefaultTemplate setEnabled(UUID tenantId, boolean enabled, UUID updatedBy);
}
