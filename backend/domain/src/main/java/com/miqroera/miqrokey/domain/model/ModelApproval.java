package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A model-access approval request: a user asks for an additional model on their
 * virtual key; an administrator approves or rejects it. An APPROVED approval
 * inserts the model into {@code virtual_key_models} (and, if the model is not
 * yet in the key's grant, into {@code project_provider_grant_models} first),
 * which the gateway snapshot picks up within one refresh interval.
 *
 * <p>
 * {@code reason} is the applicant's stated justification (optional, ≤ 500
 * chars); {@code reviewNote} is the reviewer's decision note (optional, ≤ 500
 * chars). {@code version} guards the PENDING → APPROVED/REJECTED transition
 * with an optimistic lock.
 * </p>
 */
public record ModelApproval(UUID id, UUID tenantId, UUID virtualKeyId, String modelId, UUID requestedBy,
        ModelApprovalStatus status, UUID reviewedBy, String reason, String reviewNote, long version, Instant createdAt,
        Instant updatedAt) {
}
