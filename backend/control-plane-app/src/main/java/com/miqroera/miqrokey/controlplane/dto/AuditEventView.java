package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One admin audit chain link as served to admin surfaces (G5.4): the chain
 * hashes and request ids are never serialized (integrity proof lives in the
 * table, not the API). Read-side decorations never touch the stored chain:
 * {@code targetName} resolves {@code (targetType, targetId)} (#389) and
 * {@code actorName} resolves the actor's display name (#484) when the reference
 * still resolves inside the tenant, null otherwise.
 */
public record AuditEventView(UUID id, UUID actorId, String actorName, String action, String targetType, UUID targetId,
        String changeSummary, Instant createdAt, long chainPosition, String targetName) {
}
