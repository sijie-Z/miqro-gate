package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;

/**
 * Validation result for {@link ValidateCredentialRequest}.
 *
 * <p>
 * {@code matchesActive} compares the candidate secret against the currently
 * active version (exact SHA-256 fingerprint comparison, no decryption). When
 * the candidate matches, the credential is additionally probed against the real
 * provider ({@code providerStatus}):
 * <ul>
 * <li>{@code VALID} — the provider accepted the key (e.g. 2xx on
 * {@code /models});</li>
 * <li>{@code REJECTED} — the provider refused it (401/403);</li>
 * <li>{@code UNREACHABLE} — the provider call failed or timed out;</li>
 * <li>{@code NOT_CHECKED} — no adapter/base URL for this product, or the
 * candidate did not match the active version.</li>
 * </ul>
 */
public record ValidateCredentialResponse(boolean matchesActive, String message, String providerStatus,
        String providerMessage, Instant checkedAt) {

    public ValidateCredentialResponse(boolean matchesActive, String message) {
        this(matchesActive, message, "NOT_CHECKED", null, null);
    }
}
