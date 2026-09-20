package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.usage.CacheLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Paged usage records for one user ({@code GET /api/v1/me/usage/records}). Only
 * counts and metadata — never prompt, code, or model content.
 */
public record UsageRecordPage(List<UsageRecordView> items, long page, long size, long total) {

    /**
     * One usage row as shown to a caller.
     *
     * <p>
     * The {@code *Tokens} fields are the <b>observed</b> counts — what the gateway
     * actually recorded. The {@code net*} fields are those counts plus every
     * adjustment booked against the row (#709), i.e. the financial/reporting
     * reading; {@code adjusted} says whether any correction exists at all, so a
     * correction that has since been reversed keeps it true while the net counts
     * return to the observed ones (#774). Both are carried rather than the observed
     * fields being overwritten, so the change is additive and a reader never has to
     * guess which one they hold.
     * </p>
     *
     * <p>
     * Enrichment columns (#758): {@code providerProductName} is the 供应商 column;
     * {@code ttfbMs} / {@code wireProtocol} / {@code requestStatus} come from the
     * lifecycle trail and are null for rows without one (coalesced requests);
     * {@code cost} is the per-row figure priced from that row's own price basis
     * (#710) — the same expression the aggregates use — and {@code priced=false}
     * means the row, not the whole number, is 未定价: at least one non-zero token type
     * has no price, so {@code cost} holds only the priced dimensions and is a lower
     * bound rather than a total. It is the amount the report books for this row;
     * the flag is what says not to present it as the full amount.
     * </p>
     *
     * <p>
     * Attribution (#1128, CAA V54): {@code resolutionStatus} is the server's
     * verdict on which project the request belonged to — {@code RESOLVED_HEADER} /
     * {@code RESOLVED_SUFFIX} / {@code SOLE_BINDING} / {@code POLICY_ROUTED} /
     * {@code UNATTRIBUTED} / {@code AMBIGUOUS} — while {@code claimSource} /
     * {@code claimConfidence} are what the client <em>claimed</em>
     * ({@code prompt_url}, {@code tool_path}, … at HIGH/MEDIUM/LOW). The two are
     * kept apart on purpose: the claim is unverified input, the status is the
     * ruling, and a reader comparing them is looking at exactly the case worth
     * looking at. All three are null for rows that never went through the
     * resolution ladder (a single-binding key) and for rows older than the feature.
     * </p>
     */
    public record UsageRecordView(Instant occurredAt, String modelId, CacheLevel cacheLevel, Long inputTokens,
            Long outputTokens, Long cacheReadInputTokens, Long cacheCreationInputTokens, Long totalTokens,
            Long latencyMs, Integer upstreamStatusCode, String providerRequestId, String gatewayRequestId,
            boolean isComplete, boolean usageMissing, UUID virtualKeyId, String clientIp, Long netInputTokens,
            Long netOutputTokens, Long netCacheReadInputTokens, Long netCacheCreationInputTokens, boolean adjusted,
            String providerProductName, Long ttfbMs, String wireProtocol, String requestStatus, BigDecimal cost,
            boolean priced, String resolutionStatus, String claimSource, String claimConfidence) {
    }
}
