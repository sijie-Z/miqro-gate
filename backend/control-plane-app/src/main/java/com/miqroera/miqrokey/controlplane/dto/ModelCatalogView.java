package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/** One {@code model_catalog} row as served to admin surfaces (F18). */
public record ModelCatalogView(UUID id, UUID providerProductId, String modelId, String displayName,
        Integer contextWindow, Integer maxOutputTokens, String status, String source, long version, Instant updatedAt) {
}
