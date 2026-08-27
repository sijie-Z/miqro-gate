package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A model-access approval request: a user asks for an additional model on their
 * virtual key; an administrator approves or rejects it. Approval inserts the
 * model into {@code virtual_key_models}, which the gateway snapshot picks up
 * within one refresh interval.
 */
public record ModelApproval(UUID id, UUID tenantId, UUID virtualKeyId, String modelId, UUID requestedBy,
        ModelApprovalStatus status, UUID reviewedBy, String reviewNote, long version, Instant createdAt,
        Instant updatedAt) {
}
