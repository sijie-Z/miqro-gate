package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.RoiReportView;
import com.miqroera.miqrokey.controlplane.dto.RoiReportView.RoiDay;
import com.miqroera.miqrokey.controlplane.dto.RoiReportView.RoiTotals;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.GroupSummary;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Cache-ROI report (原始设计文档 P5.4, api-contract §5.20): the daily series of what
 * response caching saved versus what reached the upstream, derived from the
 * shared usage aggregator over the day dimension. Gives the G7.4 cache feature
 * its data — hit rates, saved money, and the effective discount — so enabling
 * decisions rest on measured returns instead of guesses. Zero cache events
 * still produce a full report (everything paid).
 */
@Service
public class AdminRoiService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AdminUsageStatsService usageStatsService;

    public AdminRoiService(AdminUsageStatsService usageStatsService) {
        this.usageStatsService = usageStatsService;
    }

    public RoiReportView report(UUID tenantId, Instant from, Instant to) {
        UsageSummary summary = usageStatsService.summary(tenantId, "day", from, to, null, null, null, null, null, null,
                null);
        GroupSummary totals = summary.totals();

        long hits = totals.requests().l1Hit() + totals.requests().l2Hit();
        long served = totals.requests().upstream() + totals.requests().coalesced() + hits;
        BigDecimal hitRatePct = pct(hits, served);
        BigDecimal paid = totals.cost().upstreamPaid();
        BigDecimal saved = totals.cost().savedByGatewayCache();
        BigDecimal savedPct = pct(saved, paid.add(saved));

        List<RoiDay> days = summary.groups().stream()
                .map(g -> new RoiDay(g.label(), g.requests().upstream() + g.requests().coalesced(),
                        g.requests().l1Hit() + g.requests().l2Hit(),
                        pct(g.requests().l1Hit() + g.requests().l2Hit(),
                                g.requests().upstream() + g.requests().coalesced() + g.requests().l1Hit()
                                        + g.requests().l2Hit()),
                        g.cost().upstreamPaid(), g.cost().savedByGatewayCache()))
                .toList();

        RoiTotals roiTotals = new RoiTotals(totals.requests().upstream(), totals.requests().coalesced(),
                totals.requests().l1Hit(), totals.requests().l2Hit(), hitRatePct, paid, saved, savedPct);
        return new RoiReportView(from, to, roiTotals, days);
    }

    /** Default window: the trailing 30 days (bounded by the shared 93-day rule). */
    public static Instant defaultFrom() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).minus(30, ChronoUnit.DAYS);
    }

    private static BigDecimal pct(long part, long whole) {
        return whole == 0
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0
                ? BigDecimal.ZERO.setScale(2)
                : part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
