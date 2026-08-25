package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Admin upstream-credential creation request (api-contract §5). Plaintext
 * secret in, masked metadata out: the response never contains the secret.
 */
public record AdminCredentialCreateRequest(@NotBlank @Size(max = 200) String name, @NotNull UUID subscriptionId,
        @NotBlank @Size(max = 512) String secret) {
}
