package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Self-service request to grant an additional model to one of the caller's
 * virtual keys (model-approval workflow, api-contract §4.6). A whitelisted
 * model is auto-approved; anything else enters the PENDING review queue.
 */
public record SubmitModelApprovalRequest(@NotNull UUID virtualKeyId, @NotBlank @Size(max = 128) String modelId,
        @Size(max = 500) String reason) {
}
