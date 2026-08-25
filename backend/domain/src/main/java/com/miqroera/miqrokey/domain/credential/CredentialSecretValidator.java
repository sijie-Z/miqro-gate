package com.miqroera.miqrokey.domain.credential;

/**
 * Validates a candidate upstream credential secret before it is persisted or
 * rotated in.
 *
 * <p>
 * The default implementation (G1.6) is a local format check
 * ({@code FormatCredentialValidator}); provider-specific validation adapters
 * (G3.x) plug into the same SPI to perform a real upstream round-trip. The
 * control plane never calls this SPI with secrets it intends to keep: on
 * failure nothing is written to the database, so a bad secret can never
 * overwrite the current credential version.
 * </p>
 */
public interface CredentialSecretValidator {

    /**
     * Validates a plaintext secret.
     *
     * @param secret
     *            the candidate secret (caller-owned; never persisted, logged, or
     *            echoed by implementations)
     * @return the validation outcome
     */
    ValidationResult validate(String secret);

    /** Outcome of {@link CredentialSecretValidator#validate(String)}. */
    record ValidationResult(boolean valid, String reason) {

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
