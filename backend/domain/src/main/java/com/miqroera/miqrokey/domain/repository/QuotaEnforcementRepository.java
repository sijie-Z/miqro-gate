package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.QuotaEnforcement;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;

import java.util.List;
import java.util.UUID;

/**
 * Access to {@code quota_enforcement} (V59, #684): the scopes currently blocked
 * by a REJECT quota rule. The evaluator reconciles one tenant at a time — read
 * the tenant's rows, then upsert the still-blocked scopes and delete the ones
 * that stopped blocking.
 */
public interface QuotaEnforcementRepository {

    List<QuotaEnforcement> findAllByTenant(UUID tenantId);

    /**
     * Insert or rewrite the (tenant, scope_type, scope_id) row; returns the
     * stored row.
     */
    QuotaEnforcement upsert(QuotaEnforcement enforcement);

    /** Removes the scope's block; true when a row was actually removed. */
    boolean deleteByScope(UUID tenantId, QuotaScopeType scopeType, UUID scopeId);
}
