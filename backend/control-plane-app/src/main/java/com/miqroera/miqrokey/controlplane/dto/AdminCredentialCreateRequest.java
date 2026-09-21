package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Admin upstream-credential creation request (api-contract §5). Plaintext
 * secret in, masked metadata out: the response never contains the secret.
 * {@code seatId} optionally binds the credential to a plan seat of the same
 * subscription (the "per-seat key" team-plan topology, #492).
 */
public record AdminCredentialCreateRequest(@NotBlank @Size(max = 200) String name, @NotNull UUID subscriptionId,
        @NotBlank @Size(max = 512) String secret, UUID seatId) {
}
