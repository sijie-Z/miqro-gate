package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.usage.CacheLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Paged usage records for one user ({@code GET /api/v1/me/usage/records}). Only
 * counts and metadata — never prompt, code, or model content.
 */
public record UsageRecordPage(List<UsageRecordView> items, long page, long size, long total) {

    public record UsageRecordView(Instant occurredAt, String modelId, CacheLevel cacheLevel, Long inputTokens,
            Long outputTokens, Long cacheReadInputTokens, Long cacheCreationInputTokens, Long totalTokens,
            Long latencyMs, Integer upstreamStatusCode, String providerRequestId, String gatewayRequestId,
            boolean isComplete, boolean usageMissing, UUID virtualKeyId) {
    }
}
