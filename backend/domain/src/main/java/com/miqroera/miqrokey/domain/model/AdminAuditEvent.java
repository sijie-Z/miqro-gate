package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only audit event recording admin actions. No foreign keys to
 * mutable tables — must survive deletions and schema migrations.
 */
public record AdminAuditEvent(UUID id, UUID tenantId, UUID actorId, String action, String targetType, UUID targetId,
        String changeSummary, String gatewayRequestId, String adminRequestId, byte[] previousEventHash,
        byte[] currentEventHash, Instant createdAt) {

    public AdminAuditEvent {
        if (previousEventHash != null) {
            previousEventHash = previousEventHash.clone();
        }
        currentEventHash = currentEventHash.clone();
    }

    @Override
    public byte[] previousEventHash() {
        return previousEventHash != null ? previousEventHash.clone() : null;
    }

    @Override
    public byte[] currentEventHash() {
        return currentEventHash.clone();
    }
}
