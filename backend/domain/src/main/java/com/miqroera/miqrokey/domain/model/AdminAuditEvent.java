package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only audit event recording admin actions. No foreign keys to
 * mutable tables — must survive deletions and schema migrations.
 */
public record AdminAuditEvent(UUID id, UUID actorId, String action, String targetType, UUID targetId,
        String changeSummary, String gatewayRequestId, String adminRequestId, byte[] previousEventHash,
        byte[] currentEventHash, Instant createdAt) {
}
