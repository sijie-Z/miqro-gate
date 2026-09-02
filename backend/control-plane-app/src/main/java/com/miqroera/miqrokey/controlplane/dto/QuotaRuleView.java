package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Quota rule with its live watermark for the current period. {@code level} is
 * the alert state derived at read time: NORMAL / WARNING (≥ warnPercent) /
 * EXCEEDED (≥ 100% of the limit). A rule never blocks — the view is the
 * consumer's visibility into the plan.
 */
public record QuotaRuleView(UUID id, QuotaScopeType scopeType, UUID scopeId, String scopeName, String scopeTag,
        QuotaMetric metric, QuotaPeriod period, long limitValue, int warnPercent, QuotaRuleStatus status, long used,
        BigDecimal usedPct, String level, Instant windowFrom, Instant windowTo, Instant createdAt, Instant updatedAt,
        long version) {
}
