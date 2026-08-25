package com.miqroera.miqrokey.domain.route;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, versioned read-only routing snapshot consumed by the gateway hot
 * path.
 *
 * <p>
 * Built periodically (default 30s) from the control-plane database and swapped
 * atomically. The gateway NEVER queries the database on the hot path; it only
 * reads this snapshot. Revocation, rotation, and grant changes take effect
 * within one refresh interval (or immediately when the control plane publishes
 * a {@code pg_notify} route-refresh event).
 * </p>
 *
 * <h2>Lookup semantics</h2>
 * <ul>
 * <li>Keys are indexed by {@code publicKeyId} for O(1) lookup.</li>
 * <li>Each key has at most one ACTIVE binding; the loader resolves it.</li>
 * <li>Credentials are indexed by id and carry the upstream base URL, the
 * product's auth scheme, and the ACTIVE version's ciphertext.</li>
 * <li>Model authorization data for {@code /v1/models}: per-key
 * ({@code virtual_key_models}), per-grant
 * ({@code project_provider_grant_models} of ACTIVE grants) and per-product
 * upstream ({@code model_catalog}, ACTIVE rows only — written exclusively from
 * successful official-API fetches, so a failed fetch keeps the last successful
 * catalog). Product codes let the gateway gate products against the signed
 * provider catalog.</li>
 * </ul>
 *
 * <h2>Security</h2> {@code secretDigest} is copied defensively. The snapshot
 * carries ciphertext only — plaintext secrets NEVER enter the snapshot; the hot
 * path decrypts the {@link EncryptedSecret} in memory (AES-256-GCM) per request
 * and zero-fills the plaintext after use.
 */
public record RouteSnapshot(long version, Instant loadedAt, Map<String, KeyRecord> keys,
        Map<UUID, BindingRecord> bindings, Map<UUID, CredentialRecord> credentials,
        Map<UUID, Set<String>> modelsByKeyId, Map<UUID, Set<String>> grantModelsByGrantId,
        Map<UUID, Set<String>> upstreamModelsByProductId, Map<UUID, String> productCodesByProductId) {

    public RouteSnapshot {
        keys = Map.copyOf(keys);
        bindings = Map.copyOf(bindings);
        credentials = Map.copyOf(credentials);
        modelsByKeyId = immutableSets(modelsByKeyId);
        grantModelsByGrantId = immutableSets(grantModelsByGrantId);
        upstreamModelsByProductId = immutableSets(upstreamModelsByProductId);
        productCodesByProductId = Map.copyOf(productCodesByProductId);
    }

    private static Map<UUID, Set<String>> immutableSets(Map<UUID, Set<String>> map) {
        return map.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                e -> Collections.unmodifiableSet(Set.copyOf(e.getValue()))));
    }

    public static RouteSnapshot empty(long version, Instant loadedAt) {
        return new RouteSnapshot(version, loadedAt, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of());
    }

    public KeyRecord key(String publicKeyId) {
        return keys.get(publicKeyId);
    }

    public BindingRecord binding(UUID keyId) {
        return bindings.get(keyId);
    }

    public CredentialRecord credential(UUID credentialId) {
        return credentials.get(credentialId);
    }

    public Set<String> models(UUID keyId) {
        return modelsByKeyId.getOrDefault(keyId, Set.of());
    }

    /**
     * Models granted to an ACTIVE grant ({@code project_provider_grant_models}).
     */
    public Set<String> grantModels(UUID grantId) {
        return grantModelsByGrantId.getOrDefault(grantId, Set.of());
    }

    /**
     * Upstream models of a product ({@code model_catalog}, ACTIVE rows only). Empty
     * until an official-API fetch has succeeded — never partially populated by a
     * failed fetch.
     */
    public Set<String> upstreamModels(UUID productId) {
        return upstreamModelsByProductId.getOrDefault(productId, Set.of());
    }

    /**
     * The product's code ({@code provider_products.product_code}); null when
     * unknown.
     */
    public String productCode(UUID productId) {
        return productCodesByProductId.get(productId);
    }

    /**
     * A routing-relevant virtual key. Never holds secret material. {@code grantId}
     * is the key's owning grant ({@code virtual_keys.grant_id}, NOT NULL) and
     * selects its {@link #grantModels(UUID)} set.
     */
    public record KeyRecord(UUID keyId, UUID tenantId, String publicKeyId, byte[] secretDigest, String cachePolicy,
            String purpose, UUID grantId) {

        public KeyRecord {
            secretDigest = secretDigest.clone();
        }

        @Override
        public byte[] secretDigest() {
            return secretDigest.clone();
        }
    }

    /**
     * The single ACTIVE label binding of a key. Resolved by the loader (DISTINCT ON
     * virtual_key_id).
     */
    public record BindingRecord(UUID keyId, UUID projectId, String projectTag, UUID credentialId, UUID productId) {
    }

    /**
     * Upstream routing target: base URL and auth scheme resolved from the product
     * catalog, plus the ACTIVE credential version's ciphertext (loaded at refresh
     * time so the hot path never touches the database).
     *
     * <p>
     * {@code authScheme} is the raw jsonb text of the product's {@code auth_scheme}
     * (e.g. {@code {"type":"bearer","header":"authorization"}}).
     * {@code encryptedSecret} is null when the credential has no ACTIVE version —
     * the gateway treats that as an unroutable credential.
     * </p>
     */
    public record CredentialRecord(UUID credentialId, UUID tenantId, UUID productId, String baseUrl, String authScheme,
            EncryptedSecret encryptedSecret) {

        public CredentialRecord {
            encryptedSecret = encryptedSecret == null
                    ? null
                    : new EncryptedSecret(encryptedSecret.ciphertext(), encryptedSecret.nonce(),
                            encryptedSecret.keyVersion());
        }

        @Override
        public EncryptedSecret encryptedSecret() {
            return encryptedSecret == null
                    ? null
                    : new EncryptedSecret(encryptedSecret.ciphertext(), encryptedSecret.nonce(),
                            encryptedSecret.keyVersion());
        }
    }

    /**
     * Equality over arrays uses reference semantics; the snapshot is treated as
     * immutable, so this is acceptable. Accessors copy defensively.
     */
    @Override
    public String toString() {
        return "RouteSnapshot[version=" + version + ", loadedAt=" + loadedAt + ", keys=" + keys.size() + ", bindings="
                + bindings.size() + ", credentials=" + credentials.size() + ", models="
                + modelsByKeyId.values().stream().mapToInt(Set::size).sum() + ", grantModels="
                + grantModelsByGrantId.values().stream().mapToInt(Set::size).sum() + ", upstreamModels="
                + upstreamModelsByProductId.values().stream().mapToInt(Set::size).sum() + ", products="
                + productCodesByProductId.size() + "]";
    }

    // Explicit equals/hashCode that include arrays by content, without leaking
    // anything sensitive beyond what the records already expose.
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof RouteSnapshot that))
            return false;
        return version == that.version && loadedAt.equals(that.loadedAt) && keys.equals(that.keys)
                && bindings.equals(that.bindings) && credentials.equals(that.credentials)
                && modelsByKeyId.equals(that.modelsByKeyId) && grantModelsByGrantId.equals(that.grantModelsByGrantId)
                && upstreamModelsByProductId.equals(that.upstreamModelsByProductId)
                && productCodesByProductId.equals(that.productCodesByProductId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(version, loadedAt, keys, bindings, credentials, modelsByKeyId,
                grantModelsByGrantId, upstreamModelsByProductId, productCodesByProductId);
    }
}
