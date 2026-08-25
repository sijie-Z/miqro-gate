package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Rotation input: the new plaintext secret. The old version is demoted to
 * DRAINING (grace window) and the new version becomes ACTIVE atomically.
 */
public record RotateCredentialRequest(@NotBlank @Size(max = 512) String secret) {
}
