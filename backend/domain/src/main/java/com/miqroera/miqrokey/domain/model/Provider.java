package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A provider (vendor or aggregation platform) such as DeepSeek, Tencent, Zhipu,
 * etc.
 */
public record Provider(UUID id, String slug, String displayName, String officialSiteUrl, String documentationUrl,
        String catalogVersion, ProviderStatus status, long version, Instant createdAt, Instant updatedAt) {
}
