package com.miqroera.miqrokey.domain.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * One double-confirmed usage deletion request (G4.4, {@code usage_deletions}
 * V11): the preview count is stored up front, confirmation requires the
 * one-time token (only its SHA-256 hash is persisted), and execution deletes
 * the window permanently with an audit trail.
 */
public record UsageDeletion(UUID id, UUID tenantId, UUID requestedBy, Instant periodFrom, Instant periodTo,
        long previewCount, byte[] confirmTokenHash, UsageDeletionStatus status, Long deletedCount, Instant executedAt,
        Instant expiresAt, Instant createdAt) {

    public UsageDeletion {
        if (id == null || tenantId == null || requestedBy == null || periodFrom == null || periodTo == null
                || confirmTokenHash == null || status == null || expiresAt == null || createdAt == null) {
            throw new IllegalArgumentException("required fields must not be null");
        }
    }
}
