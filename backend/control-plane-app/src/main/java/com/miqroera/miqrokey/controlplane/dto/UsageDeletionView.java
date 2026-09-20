package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.usage.UsageDeletion;
import com.miqroera.miqrokey.domain.usage.UsageDeletionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata-only view of a usage deletion request (G4.4, api-contract §5.6) —
 * the persisted SHA-256 of the one-time confirmation token stays off the JSON
 * surface: the plaintext token appears exactly once, in the create response
 * ({@code UsageDeletionService.DeletionRequest}), and neither it nor its digest
 * is ever echoed by the confirm or list endpoints again. Mirrors the credential
 * rule that only a short fingerprint prefix may be echoed, never the full
 * digest.
 */
public record UsageDeletionView(UUID id, UUID tenantId, UUID requestedBy, Instant periodFrom, Instant periodTo,
        long previewCount, UsageDeletionStatus status, Long deletedCount, Instant executedAt, Instant expiresAt,
        Instant createdAt) {

    public static UsageDeletionView from(UsageDeletion deletion) {
        return new UsageDeletionView(deletion.id(), deletion.tenantId(), deletion.requestedBy(), deletion.periodFrom(),
                deletion.periodTo(), deletion.previewCount(), deletion.status(), deletion.deletedCount(),
                deletion.executedAt(), deletion.expiresAt(), deletion.createdAt());
    }
}
