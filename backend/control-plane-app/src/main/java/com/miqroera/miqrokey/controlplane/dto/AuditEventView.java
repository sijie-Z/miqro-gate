package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One admin audit chain link as served to admin surfaces (G5.4): the chain
 * hashes and request ids are never serialized (integrity proof lives in the
 * table, not the API).
 */
public record AuditEventView(UUID id, UUID actorId, String action, String targetType, UUID targetId,
        String changeSummary, Instant createdAt, long chainPosition) {
}
