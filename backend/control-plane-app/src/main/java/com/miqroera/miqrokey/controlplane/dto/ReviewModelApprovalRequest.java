package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.Size;

/**
 * Reviewer's decision note for approve/reject. Optional; stored in
 * {@code model_approval.review_note} (≤ 500 chars) and shown with the history.
 */
public record ReviewModelApprovalRequest(@Size(max = 500) String reviewNote) {
}
