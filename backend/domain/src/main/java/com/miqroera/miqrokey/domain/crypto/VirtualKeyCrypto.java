package com.miqroera.miqrokey.domain.crypto;

import java.util.UUID;

/**
 * Cryptographic operations for Virtual Key lifecycle.
 *
 * <h2>One-time display</h2> {@link #generate(UUID)} returns a
 * {@link VirtualKeyMaterial} that contains the full display string, raw secret,
 * and HMAC digest. The raw secret and full display string are available only
 * through the returned material; the provider clears its internal copy of the
 * raw secret immediately after digest computation. Callers should display the
 * material once and then discard it.
 *
 * <h2>Domain separation</h2> The HMAC message includes the public key ID, raw
 * secret, and tenant ID. This means a Virtual Key created for tenant A cannot
 * be validated against tenant B even if both tenants share the same HMAC key
 * ring.
 *
 * <h2>Constant-time validation</h2>
 * {@link #validateConstantTime(String, byte[], byte[], UUID)} uses
 * {@link java.security.MessageDigest#isEqual(byte[], byte[])} and traverses ALL
 * known HMAC key versions without early exit. This prevents timing
 * side-channels from revealing which version matched.
 *
 * <h2>Digest-only storage</h2> Only the HMAC digest is persisted (in
 * {@code virtual_keys.secret_digest}). The raw secret and full display string
 * are never stored in the database.
 */
public interface VirtualKeyCrypto {

    /**
     * Generates a new Virtual Key, bound to the given tenant.
     *
     * <p>
     * The returned {@link VirtualKeyMaterial} contains the one-time display string,
     * raw secret (caller-owned, must be cleared), and HMAC digest for database
     * storage. The provider zeros its internal copy of the raw secret before
     * returning.
     * </p>
     *
     * @param tenantId
     *            the tenant that owns this key
     * @return complete material for one-time display
     */
    VirtualKeyMaterial generate(UUID tenantId);

    /**
     * Validates a presented Virtual Key against a stored digest using constant-time
     * comparison across all known HMAC key versions.
     *
     * <p>
     * All versions are always traversed — no early exit — to prevent timing
     * side-channels.
     * </p>
     *
     * @param publicKeyId
     *            the public key ID from the Virtual Key
     * @param rawSecret
     *            the raw secret portion (caller-owned, will not be copied)
     * @param expectedDigest
     *            the digest stored in the database
     * @param tenantId
     *            the tenant that owns this key (must match original)
     * @return true if the digest matches under any known HMAC key version
     */
    boolean validateConstantTime(String publicKeyId, byte[] rawSecret, byte[] expectedDigest, UUID tenantId);

    /**
     * Computes the HMAC digest for a public key ID + raw secret + tenant triple
     * under the active HMAC key version.
     *
     * @param publicKeyId
     *            the public key ID
     * @param rawSecret
     *            the raw secret (caller-owned)
     * @param tenantId
     *            the owning tenant
     * @return HMAC-SHA-256 digest (32 bytes)
     */
    byte[] computeDigest(String publicKeyId, byte[] rawSecret, UUID tenantId);

    /**
     * Returns the identifier of the active HMAC key version.
     */
    String activeKeyVersion();
}
