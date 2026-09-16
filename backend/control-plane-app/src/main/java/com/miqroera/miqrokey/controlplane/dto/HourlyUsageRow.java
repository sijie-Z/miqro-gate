package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One hour bucket of the hourly usage report (#634): counts for one project
 * and, when a cross-tab dimension is requested, one user or team. Token totals
 * follow the same convention as the summary aggregator
 * ({@code input + output + cacheRead + cacheCreation}).
 */
public record HourlyUsageRow(Instant hourStart, UUID projectId, String projectLabel, UUID dimensionId,
        String dimensionLabel, long requests, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheCreationTokens, long totalTokens) {
}
