package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A usage quota plan (V23, {@code quota_rules}): limit of a metric (TOKENS |
 * REQUESTS | COST) per UTC period (DAILY | WEEKLY | MONTHLY | YEARLY) for one
 * scope (USER | PROJECT), with a warn-threshold percentage and an exceeded
 * action: ALERT (default — the rule only reports its watermark) or REJECT (an
 * exceeded rule blocks the covered user/project at the gateway with 429 until
 * the window resets or the limit is raised; #684/ADR-0020). The current-period
 * watermark is computed at read time from usage events, so the table stores
 * only the plan.
 */
public record QuotaRule(UUID id, UUID tenantId, QuotaScopeType scopeType, UUID scopeId, QuotaMetric metric,
        QuotaPeriod period, QuotaAction action, long limitValue, int warnPercent, QuotaRuleStatus status,
        UUID createdBy, long version, Instant createdAt, Instant updatedAt) {

    public QuotaRule {
        if (action == null) {
            // Existing rows, the template path and older clients carry no action.
            action = QuotaAction.ALERT;
        }
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
