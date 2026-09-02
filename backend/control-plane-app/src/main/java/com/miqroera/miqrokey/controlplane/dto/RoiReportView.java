package com.miqroera.miqrokey.controlplane.dto;

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

    public record RoiTotals(long upstreamRequests, long coalescedRequests, long l1Hits, long l2Hits,
            BigDecimal hitRatePct, BigDecimal paidCost, BigDecimal savedCost, BigDecimal savedPct) {
    }

    public record RoiDay(String date, long upstreamRequests, long hitRequests, BigDecimal hitRatePct,
            BigDecimal paidCost, BigDecimal savedCost) {
    }
}
