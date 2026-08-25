package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Tests a candidate secret against a credential without persisting anything
 * (api-contract §5: a credential test must never auto-write an unsaved value).
 */
public record ValidateCredentialRequest(@NotBlank @Size(max = 512) String secret) {
}
