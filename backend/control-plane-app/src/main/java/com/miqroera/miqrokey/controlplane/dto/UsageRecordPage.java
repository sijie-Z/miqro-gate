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
     * reading; {@code adjusted} says whether any correction exists at all. Both are
     * carried rather than the observed fields being overwritten, so the change is
     * additive and a reader never has to guess which one they hold.
     * </p>
     *
     * <p>
     * Enrichment columns (#758): {@code providerProductName} is the 供应商 column;
     * {@code ttfbMs} / {@code wireProtocol} / {@code requestStatus} come from the
     * lifecycle trail and are null for rows without one (coalesced requests);
     * {@code cost} is the per-row estimate priced with the same table as the
     * aggregates, and {@code priced=false} means "未定价" — at least one non-zero
     * token type has no snapshot, so the cost number must not be trusted as 0.
     * </p>
     */
    public record UsageRecordView(Instant occurredAt, String modelId, CacheLevel cacheLevel, Long inputTokens,
            Long outputTokens, Long cacheReadInputTokens, Long cacheCreationInputTokens, Long totalTokens,
            Long latencyMs, Integer upstreamStatusCode, String providerRequestId, String gatewayRequestId,
            boolean isComplete, boolean usageMissing, UUID virtualKeyId, String clientIp, Long netInputTokens,
            Long netOutputTokens, Long netCacheReadInputTokens, Long netCacheCreationInputTokens, boolean adjusted,
            String providerProductName, Long ttfbMs, String wireProtocol, String requestStatus, BigDecimal cost,
            boolean priced) {
    }
}
