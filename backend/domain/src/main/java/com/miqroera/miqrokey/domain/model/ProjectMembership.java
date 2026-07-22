package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Membership linking a user to a project.
 */
public record ProjectMembership(UUID tenantId, UUID projectId, UUID userId, UUID createdBy, Instant createdAt) {
}
