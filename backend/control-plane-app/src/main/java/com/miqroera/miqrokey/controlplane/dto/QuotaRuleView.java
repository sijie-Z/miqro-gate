package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.QuotaAction;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingGap;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Quota rule with its live watermark for the current period. {@code level} is
 * the alert state derived at read time: NORMAL / WARNING (≥ warnPercent) /
 * NEAR_LIMIT (≥ 90%, fixed guidance tier) / EXCEEDED (≥ 100% of the limit),
 * judged in that severity order. {@code used} is an integral count for
 * TOKENS/REQUESTS and the priced CNY cost for COST (#683). {@code action} is
 * what an exceeded rule does: ALERT (watermark only) or REJECT (the gateway
 * answers 429 for the covered scope; #684/ADR-0020).
 *
 * <p>
 * For a COST rule, {@code pricingStatus} and {@code unpriced} say how much of
 * the window the number could actually cover (#943): {@code COMPLETE} means the
 * cost is the total, {@code PARTIAL} / {@code UNAVAILABLE} mean it is a lower
 * bound — the same two fields the usage API has sent since #766, so the pages
 * describe one window the same way. They are {@code null} for TOKENS/REQUESTS
 * rules, which make no claim about money.
 */
public record QuotaRuleView(UUID id, QuotaScopeType scopeType, UUID scopeId, String scopeName, String scopeTag,
        QuotaMetric metric, QuotaPeriod period, QuotaAction action, long limitValue, int warnPercent,
        QuotaRuleStatus status, BigDecimal used, BigDecimal usedPct, String level, PricingStatus pricingStatus,
        PricingGap unpriced, Instant windowFrom, Instant windowTo, Instant createdAt, Instant updatedAt, long version) {
}
