package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One admin audit chain link as served to admin surfaces (G5.4): the chain
 * hashes and request ids are never serialized (integrity proof lives in the
 * table, not the API). {@code targetName} is a read-side decoration (#389): the
 * resolved resource name for {@code (targetType, targetId)} when the reference
 * still resolves inside the tenant, null otherwise — the stored chain is never
 * touched.
 */
public record AuditEventView(UUID id, UUID actorId, String action, String targetType, UUID targetId,
        String changeSummary, Instant createdAt, long chainPosition, String targetName) {
}
