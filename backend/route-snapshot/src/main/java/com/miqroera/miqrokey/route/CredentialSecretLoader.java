package com.miqroera.miqrokey.route;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

/**
 * Loads a credential's ACTIVE version ciphertext and decrypts it with the
 * tenant/credential-bound AAD. BLOCKING — must never run on the Reactor event
 * loop; callers wrap it in {@code Mono.fromCallable(..., boundedScheduler)}.
 *
 * <h2>Secret lifecycle</h2> The returned plaintext is caller-owned; the caller
 * MUST zero-fill it after use (see {@link SecretWiping}). Only the ACTIVE
 * version is decrypted (rotation semantics: the new version is used for new
 * requests, draining requests keep their version).
 */
public final class CredentialSecretLoader {

    private static final Logger log = LoggerFactory.getLogger(CredentialSecretLoader.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final KeyEncryptionProvider keyEncryptionProvider;

    public CredentialSecretLoader(NamedParameterJdbcTemplate jdbc, KeyEncryptionProvider keyEncryptionProvider) {
        this.jdbc = jdbc;
        this.keyEncryptionProvider = keyEncryptionProvider;
    }

    /**
     * Loads and decrypts the ACTIVE version of a credential.
     *
     * @param tenantId
     *            tenant bound to the credential (AAD)
     * @param credentialId
     *            credential whose active version is loaded
     * @return decrypted plaintext (caller-owned; zero-fill after use) or null when
     *         the credential/version is missing or inactive
     * @throws com.miqroera.miqrokey.domain.crypto.impl.CryptoOperationException
     *             on AEAD failure (key mismatch, tampering)
     */
    public byte[] loadActiveSecret(UUID tenantId, UUID credentialId) {
        var params = new MapSqlParameterSource().addValue("credentialId", credentialId).addValue("tenantId", tenantId);
        Row row = jdbc.query("""
                SELECT v.encrypted_secret, v.nonce, v.encryption_key_version
                FROM upstream_credential_versions v
                JOIN upstream_credentials c ON c.tenant_id = v.tenant_id AND c.id = v.credential_id
                WHERE v.tenant_id = :tenantId AND v.credential_id = :credentialId
                  AND v.status = 'ACTIVE'
                  AND c.active_version_id = v.id
                LIMIT 1
                """, params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new Row(rs.getBytes("encrypted_secret"), rs.getBytes("nonce"),
                    rs.getString("encryption_key_version"));
        });
        if (row == null) {
            log.warn("No ACTIVE credential version for credential {}", credentialId);
            return null;
        }
        EncryptedSecret secret = new EncryptedSecret(row.encryptedSecret(), row.nonce(), row.encryptionKeyVersion());
        byte[] plaintext = keyEncryptionProvider.decrypt(secret, tenantId, credentialId);
        if (log.isDebugEnabled()) {
            log.debug("Decrypted credential {} (keyVersion={})", credentialId, row.encryptionKeyVersion());
        }
        return plaintext;
    }

    private record Row(byte[] encryptedSecret, byte[] nonce, String encryptionKeyVersion) {
    }
}
