package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingGap;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingStatus;
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
 *
 * <p>
 * "Priced" is a real qualifier, so the reading carries its own provenance
 * (#943): a COST watermark whose window holds usage that no price snapshot
 * covers is a <b>lower bound</b>, and it now says so instead of reading as a
 * definite zero. {@code level} is deliberately unchanged by that gap — what an
 * unpriceable window should do to a REJECT rule is a product decision (#943
 * question 2), not something a display fix settles.
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
        BigDecimal usedPct = percentage(used, rule.limitValue());
        // Severity order: full > fixed 90% guidance tier > the rule's own warn.
        String level = reachesTheLimit(used, rule.limitValue())
                ? EXCEEDED
                : usedPct.compareTo(BigDecimal.valueOf(NEAR_LIMIT_PERCENT)) >= 0
                        ? "NEAR_LIMIT"
                        : usedPct.compareTo(BigDecimal.valueOf(rule.warnPercent())) >= 0 ? "WARNING" : "NORMAL";
        // #943: only a COST rule claims anything about money, so only it carries the
        // pricing status — a TOKENS rule counts its tokens in full however the same
        // window happens to be priced, and a caveat there would be noise.
        boolean cost = rule.metric() == QuotaMetric.COST;
        return new Watermark(used, usedPct, level, cost ? summary.totals().pricingStatus() : null,
                cost ? summary.totals().unpriced() : null, window.from(), window.to());
    }

    /**
     * The enforcement boundary on the raw reading: the reading {@code used} reached
     * {@code limitValue} (#684). The percentage is rounded before it is compared,
     * so the verdict is "at or above the limit", not "strictly above" — the
     * sticky-verdict evaluator has to decide a recorded block by the same boundary
     * that raised it, or a limit raised to exactly the recorded reading would read
     * as a recovery. {@code
     * limitValue} is guaranteed positive by the {@code quota_rules} check
     * constraint.
     */
    static boolean reachesTheLimit(BigDecimal used, long limitValue) {
        return percentage(used, limitValue).compareTo(HUNDRED) >= 0;
    }

    private static BigDecimal percentage(BigDecimal used, long limitValue) {
        return used.multiply(HUNDRED).divide(BigDecimal.valueOf(limitValue), 2, RoundingMode.HALF_UP);
    }

    record Watermark(BigDecimal used, BigDecimal usedPct, String level, PricingStatus pricingStatus,
            PricingGap unpriced, Instant from, Instant to) {

        /** The enforcement verdict: usage reached 100% of the limit (#684). */
        boolean exceeded() {
            return EXCEEDED.equals(level);
        }
    }
}
