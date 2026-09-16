package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A usage quota plan (V23, {@code quota_rules}): limit of a metric (TOKENS |
 * REQUESTS | COST) per UTC period (DAILY | WEEKLY | MONTHLY | YEARLY) for one
 * scope (USER | PROJECT), with a warn-threshold percentage. The current-period
 * watermark is computed at read time from usage events, so the table stores
 * only the plan.
 *
 * <p>Since V59 (#684) the plan also carries {@link QuotaRuleEnforcement}: rules
 * stay alert-only by default, and only an explicit {@code REJECT} lets the
 * control plane publish the scope as blocked. Even then nothing is done to the
 * credential — the request is refused, the key stays valid (soft landing).
 */
public record QuotaRule(UUID id, UUID tenantId, QuotaScopeType scopeType, UUID scopeId, QuotaMetric metric,
        QuotaPeriod period, long limitValue, int warnPercent, QuotaRuleEnforcement enforcement, QuotaRuleStatus status,
        UUID createdBy, long version, Instant createdAt, Instant updatedAt) {

    /** Compatibility constructor: pre-V59 plan, i.e. alert-only (#684). */
    public QuotaRule(UUID id, UUID tenantId, QuotaScopeType scopeType, UUID scopeId, QuotaMetric metric,
            QuotaPeriod period, long limitValue, int warnPercent, QuotaRuleStatus status, UUID createdBy, long version,
            Instant createdAt, Instant updatedAt) {
        this(id, tenantId, scopeType, scopeId, metric, period, limitValue, warnPercent, QuotaRuleEnforcement.ALERT,
                status, createdBy, version, createdAt, updatedAt);
    }

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
        if (enforcement == null) {
            throw new IllegalArgumentException("enforcement is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }
}
