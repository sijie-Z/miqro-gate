package com.miqroera.miqrokey.domain.crypto.impl;

/**
 * Unchecked exception for cryptographic operation failures.
 *
 * <p>
 * <strong>Security property:</strong> The exception message contains only a
 * stable error code (e.g. {@code CRYPTO_ENCRYPT_001}) and never includes key
 * material, plaintext, ciphertext, nonces, or JCE provider diagnostic strings
 * that could leak cryptographic state.
 * </p>
 *
 * <p>
 * The original {@link Throwable} cause is stored for debugging but its message
 * is not propagated through {@link #getMessage()}.
 * </p>
 *
 * <p>
 * Error code ranges:
 * <ul>
 * <li>{@code CRYPTO_ENCRYPT_xxx} — AES-GCM encryption failures</li>
 * <li>{@code CRYPTO_DECRYPT_xxx} — AES-GCM decryption failures (includes AEAD
 * tag mismatch)</li>
 * <li>{@code CRYPTO_HMAC_xxx} — HMAC-SHA-256 computation failures</li>
 * <li>{@code CRYPTO_KEY_xxx} — key material validation failures</li>
 * <li>{@code CRYPTO_CONFIG_xxx} — provider configuration failures</li>
 * </ul>
 * </p>
 */
public class CryptoOperationException extends RuntimeException {

    private final String errorCode;

    /**
     * Creates a new exception with the given stable error code and sanitized
     * developer-facing message.
     *
     * @param errorCode
     *            stable error code (e.g. {@code CRYPTO_DECRYPT_001})
     * @param message
     *            sanitized message (must NOT contain key material or plaintext)
     */
    public CryptoOperationException(String errorCode, String message) {
        super("[" + errorCode + "] " + message);
        this.errorCode = errorCode;
    }

    /**
     * Creates a new exception with a stable error code, sanitized message, and the
     * original JCE/general-security cause for debugging.
     *
     * <p>
     * <strong>Important:</strong> The cause's message is NOT included in
     * {@link #getMessage()} to prevent JCE provider diagnostics from leaking into
     * logs.
     * </p>
     *
     * @param errorCode
     *            stable error code
     * @param message
     *            sanitized message
     * @param cause
     *            the triggering exception (its message is suppressed)
     */
    public CryptoOperationException(String errorCode, String message, Throwable cause) {
        super("[" + errorCode + "] " + message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable error code (e.g. {@code CRYPTO_DECRYPT_001}) for
     * programmatic handling and log filtering.
     */
    public String errorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "CryptoOperationException[" + errorCode + "]";
    }
}
