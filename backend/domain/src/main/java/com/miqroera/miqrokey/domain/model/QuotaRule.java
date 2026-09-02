package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A usage quota plan (V23, {@code quota_rules}): limit of a metric (TOKENS |
 * REQUESTS) per UTC period (DAILY | WEEKLY | MONTHLY) for one scope (USER |
 * PROJECT), with a warn-threshold percentage. Alerting-only — a rule never
 * blocks traffic, matching the product decision "no budget blocking". The
 * current-period watermark is computed at read time from usage events, so the
 * table stores only the plan.
 */
public record QuotaRule(UUID id, UUID tenantId, QuotaScopeType scopeType, UUID scopeId, QuotaMetric metric,
        QuotaPeriod period, long limitValue, int warnPercent, QuotaRuleStatus status, UUID createdBy, long version,
        Instant createdAt, Instant updatedAt) {

    public QuotaRule {
        if (id == null || tenantId == null || scopeType == null || scopeId == null || metric == null || period == null
                || createdBy == null) {
            throw new IllegalArgumentException("id/tenantId/scopeType/scopeId/metric/period/createdBy are required");
        }
        if (limitValue <= 0) {
            throw new IllegalArgumentException("limitValue must be positive");
        }
        if (warnPercent < 1 || warnPercent > 99) {
            throw new IllegalArgumentException("warnPercent must be within 1..99");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }
}
