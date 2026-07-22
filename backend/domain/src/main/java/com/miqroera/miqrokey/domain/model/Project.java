package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A project is the core entity for authorization, usage, and cost attribution.
 */
public record Project(UUID id, UUID tenantId, String code, String name, String description, String costCenter,
        ProjectStatus status, long version, Instant createdAt, Instant updatedAt) {
}
