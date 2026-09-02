package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.ModelApprovalStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe view of a model-approval request for both the requester (my requests)
 * and the reviewer (admin queue). Carries display metadata only: masked key
 * display, project tag, and display names — never key material.
 */
public record ModelApprovalView(UUID id, UUID virtualKeyId, String keyName, String keyDisplay, String projectTag,
        String modelId, String reason, ModelApprovalStatus status, UUID requesterId, String requesterName,
        String reviewNote, String reviewedByName, Instant createdAt, Instant updatedAt) {
}
