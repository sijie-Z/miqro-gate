package com.miqroera.miqrokey.spi;

import java.time.Instant;

/**
 * Result of {@link ProviderProductAdapter#validateCredential}. The check is
 * side-effect free: it never writes anything and never returns the secret.
 *
 * @param valid
 *            whether the credential is accepted by the provider
 * @param message
 *            human-readable detail; {@code null} when {@code valid}
 * @param checkedAt
 *            when the check was performed
 */
public record CredentialCheck(boolean valid, String message, Instant checkedAt) {

    public CredentialCheck {
        if (checkedAt == null) {
            throw new IllegalArgumentException("checkedAt must not be null");
        }
    }

    public static CredentialCheck valid(Instant checkedAt) {
        return new CredentialCheck(true, null, checkedAt);
    }

    public static CredentialCheck invalid(String message, Instant checkedAt) {
        return new CredentialCheck(false, message, checkedAt);
    }
}
