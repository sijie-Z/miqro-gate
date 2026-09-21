package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One global configuration entry (P3.3, {@code config_entries} V19): a grouped
 * key-value pair managed by admins. Non-secret configuration only — secrets
 * stay in the env / encrypted-secret pipeline.
 */
public record ConfigEntry(UUID id, UUID tenantId, String groupName, String key, String value, String description,
        UUID updatedBy, long version, Instant createdAt, Instant updatedAt) {

    public ConfigEntry {
        if (id == null || tenantId == null || groupName == null || groupName.isBlank() || key == null || key.isBlank()
                || value == null) {
            throw new IllegalArgumentException("id/tenantId/groupName/key/value are required");
        }
    }
}
