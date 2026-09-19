package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingGap;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.PricingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Cache-ROI report (原始设计文档 P5.4): what response caching saved versus what still
 * reached the upstream, over a window plus a per-day series. Amounts are
 * decimal strings of the underlying money values.
 *
 * <ul>
 * <li>{@code hitRatePct}: cached requests (L1+L2) as a share of every served
 * request (upstream + coalesced + hits).</li>
 * <li>{@code savedPct}: savedCost as a share of (paidCost + savedCost) — the
 * hypothetical spend had the cache not existed.</li>
 * </ul>
 */
public record RoiReportView(Instant from, Instant to, RoiTotals totals, List<RoiDay> byDay) {

    /**
     * @param pricingStatus
     *            whether every token dimension that took part had a price; anything
     *            but {@code COMPLETE} means {@code paidCost} / {@code savedCost}
     *            are short of the whole (see {@code unpriced}).
     * @param savedPct
     *            {@code savedCost} as a share of the hypothetical spend;
     *            <b>null</b> when there is no cost basis at all, because that share
     *            is then undefined rather than zero.
     */
    public record RoiTotals(long upstreamRequests, long coalescedRequests, long l1Hits, long l2Hits,
            BigDecimal hitRatePct, BigDecimal paidCost, BigDecimal savedCost, BigDecimal savedPct,
            PricingStatus pricingStatus, PricingGap unpriced) {
    }

    public record RoiDay(String date, long upstreamRequests, long hitRequests, BigDecimal hitRatePct,
            BigDecimal paidCost, BigDecimal savedCost) {
    }
}
