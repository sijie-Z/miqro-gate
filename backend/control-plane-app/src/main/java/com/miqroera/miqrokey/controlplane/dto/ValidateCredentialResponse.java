package com.miqroera.miqrokey.controlplane.dto;

/**
 * Validation result for {@link ValidateCredentialRequest}: whether the
 * candidate secret equals the currently active version (exact SHA-256
 * fingerprint comparison, no decryption). {@code message} carries the reason
 * when the secret is well-formed but does not match.
 */
public record ValidateCredentialResponse(boolean matchesActive, String message) {
}
