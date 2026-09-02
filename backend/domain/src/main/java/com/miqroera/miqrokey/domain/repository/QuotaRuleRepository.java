package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code quota_rules} (V23): usage quota plans per (tenant, scope,
 * metric, period). Upsert is keyed on that tuple — the admin UI edits a rule's
 * plan in place.
 */
public interface QuotaRuleRepository {

    /**
     * Insert or update the (tenant, scope, metric, period) row; returns the stored
     * row.
     */
    QuotaRule upsert(QuotaRule rule);

    /**
     * Insert only when the (tenant, scope, metric, period) tuple is free; returns
     * empty when a row already exists. Used by the default-quota snapshot copy
     * (Tencent doc 135489) so manual rules always win over the template.
     */
    Optional<QuotaRule> insertIfAbsent(QuotaRule rule);

    Optional<QuotaRule> findById(UUID tenantId, UUID id);

    Optional<QuotaRule> findByKey(UUID tenantId, QuotaScopeType scopeType, UUID scopeId, QuotaMetric metric,
            QuotaPeriod period);

    List<QuotaRule> findAllByTenant(UUID tenantId);

    /** True when a row was removed. */
    boolean delete(UUID tenantId, UUID id);
}
