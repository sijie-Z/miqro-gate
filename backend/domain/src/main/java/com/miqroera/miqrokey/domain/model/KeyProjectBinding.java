package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The authorization authority for label routing: which project a virtual key
 * may address. The gateway routes a presented key's label to the project only
 * if an ACTIVE binding exists here — the label itself is NOT trusted.
 */
public record KeyProjectBinding(UUID id, UUID tenantId, UUID virtualKeyId, UUID projectId,
        KeyProjectBindingStatus status, long version, Instant createdAt, Instant updatedAt) {
}
