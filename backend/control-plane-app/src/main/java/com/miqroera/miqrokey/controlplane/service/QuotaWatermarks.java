package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Live watermark of a quota rule for its current UTC window (#684). The
 * admin/self-service views and the soft-landing enforcement evaluator must
 * derive the same level from the same numbers, so both go through this
 * component instead of each aggregating usage on their own. COST watermarks the
 * priced upstream cost of the window in CNY (#683).
 */
@Component
class QuotaWatermarks {

    /** Fixed guidance tier (#683): Tencent's "即将超限" state sits at 90%. */
    static final int NEAR_LIMIT_PERCENT = 90;
    static final String EXCEEDED = "EXCEEDED";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AdminUsageStatsService usageStatsService;

    QuotaWatermarks(AdminUsageStatsService usageStatsService) {
        this.usageStatsService = usageStatsService;
    }

    Watermark evaluate(UUID tenantId, QuotaRule rule) {
        AdminQuotaRuleService.Window window = AdminQuotaRuleService.window(rule.period());
        // Uncapped variant (#683): a YEARLY window spans 365 days, beyond the
        // 93-day guard on the public usage API. Observed reading (#1002): a
        // watermark is a verdict on what the gateway measured, so a usage
        // adjustment booked afterwards must not raise or lower it — the ledger
        // (#709) belongs to the reporting/billing numbers, not to this one.
        UsageSummary summary = usageStatsService.summaryObservedUncapped(tenantId, "project", window.from(),
                window.to(), rule.scopeType() == QuotaScopeType.USER ? rule.scopeId() : null,
                rule.scopeType() == QuotaScopeType.PROJECT ? rule.scopeId() : null);
        BigDecimal used = switch (rule.metric()) {
            case TOKENS -> BigDecimal.valueOf(summary.totals().tokens().total());
            case REQUESTS -> BigDecimal.valueOf(summary.totals().requests().upstream());
            case COST -> summary.totals().cost().upstreamPaid();
        };
        BigDecimal usedPct = used.multiply(HUNDRED).divide(BigDecimal.valueOf(rule.limitValue()), 2,
                RoundingMode.HALF_UP);
        // Severity order: full > fixed 90% guidance tier > the rule's own warn.
        String level = usedPct.compareTo(HUNDRED) >= 0
                ? EXCEEDED
                : usedPct.compareTo(BigDecimal.valueOf(NEAR_LIMIT_PERCENT)) >= 0
                        ? "NEAR_LIMIT"
                        : usedPct.compareTo(BigDecimal.valueOf(rule.warnPercent())) >= 0 ? "WARNING" : "NORMAL";
        return new Watermark(used, usedPct, level, window.from(), window.to());
    }

    record Watermark(BigDecimal used, BigDecimal usedPct, String level, Instant from, Instant to) {

        /** The enforcement verdict: usage reached 100% of the limit (#684). */
        boolean exceeded() {
            return EXCEEDED.equals(level);
        }
    }
}
