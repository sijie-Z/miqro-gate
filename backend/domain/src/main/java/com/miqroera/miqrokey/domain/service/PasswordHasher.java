package com.miqroera.miqrokey.domain.service;

/**
 * Password hashing service using Argon2id.
 *
 * <p>
 * Implementations must use Argon2id with memory-hard parameters suitable for
 * server-side authentication. The hash output is self-contained (includes salt,
 * parameters, and hash) and can be stored directly in the {@code password_hash}
 * column.
 * </p>
 */
public interface PasswordHasher {

    /**
     * Hash a plaintext password with a random salt using Argon2id. The returned
     * byte array is the full encoded hash string (UTF-8 bytes).
     */
    byte[] hash(String password);

    /**
     * Verify a plaintext password against a previously computed hash. Uses
     * constant-time comparison where applicable.
     */
    boolean verify(String password, byte[] hash);

    /**
     * Verify a plaintext password against a stable pre-computed dummy Argon2 hash
     * of identical cost. Always returns false. Used during login to prevent timing
     * discrimination when the real user record cannot be located or is ineligible
     * for a real password check (unknown user, disabled, locked).
     *
     * <p>
     * Implementations must perform work comparable to a real {@link #verify}
     * invocation so that an attacker cannot measure whether the hash was "real" or
     * "dummy".
     * </p>
     */
    boolean verifyAgainstDummy(String password);

    /**
     * Check whether the given hash uses parameters that should be upgraded. Returns
     * true if the hash was produced with older/weaker parameters and should be
     * re-hashed on next successful authentication.
     */
    boolean needsRehash(byte[] hash);
}
