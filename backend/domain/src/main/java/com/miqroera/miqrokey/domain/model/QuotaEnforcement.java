package com.miqroera.miqrokey.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A scope currently blocked by a {@link QuotaRuleEnforcement#REJECT} quota rule
 * (V59, {@code quota_enforcement}, #684). At most one row per (tenant, scope) —
 * the unique constraint is what makes the evaluator's reconcile idempotent.
 *
 * <p>This is a projection of the rule plus its current-period watermark, not a
 * second source of truth: {@code usedValue}/{@code usedPercent} snapshot the
 * watermark at the moment the block was materialised, and the row carries
 * {@code windowTo} so the gateway can tell when the block expires. It is
 * deleted as soon as the limit goes back above usage, the rule stops being
 * {@code REJECT}, or the period rolls over.
 *
 * <p>Nothing here touches the virtual key itself — only requests are refused.
 */
public record QuotaEnforcement(UUID id, UUID tenantId, QuotaScopeType scopeType, UUID scopeId, UUID ruleId,
        QuotaMetric metric, QuotaPeriod period, long limitValue, BigDecimal usedValue, BigDecimal usedPercent,
        Instant windowFrom, Instant windowTo, Instant createdAt, Instant updatedAt) {

    public QuotaEnforcement {
        if (id == null || tenantId == null || scopeType == null || scopeId == null || ruleId == null || metric == null
                || period == null) {
            throw new IllegalArgumentException("id/tenantId/scopeType/scopeId/ruleId/metric/period are required");
        }
        if (limitValue <= 0) {
            throw new IllegalArgumentException("limitValue must be positive");
        }
        if (usedValue == null || usedPercent == null) {
            throw new IllegalArgumentException("usedValue/usedPercent are required");
        }
        if (windowFrom == null || windowTo == null) {
            throw new IllegalArgumentException("windowFrom/windowTo are required");
        }
        if (!windowTo.isAfter(windowFrom)) {
            throw new IllegalArgumentException("windowTo must be after windowFrom");
        }
    }

    /**
     * True when this row still describes the given rule and period, i.e. the
     * block can be kept as-is. Any difference means the evaluator has to rewrite
     * it (limit raised, metric switched, period rolled over, different rule won).
     */
    public boolean matches(UUID otherRuleId, QuotaMetric otherMetric, QuotaPeriod otherPeriod, long otherLimitValue,
            Instant otherWindowFrom, Instant otherWindowTo) {
        return ruleId.equals(otherRuleId) && metric == otherMetric && period == otherPeriod
                && limitValue == otherLimitValue && windowFrom.equals(otherWindowFrom)
                && windowTo.equals(otherWindowTo);
    }
}
