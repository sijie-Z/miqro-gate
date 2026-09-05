package com.miqroera.miqrokey.domain.route;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import com.miqroera.miqrokey.domain.model.RetentionConfig;

import java.time.Instant;
import java.util.Collections;
import java.net.URI;
import java.util.List;
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
        Map<UUID, Set<String>> upstreamModelsByProductId, Map<UUID, String> productCodesByProductId,
        Map<UUID, UUID> providerIdsByProductId, Map<String, ConsumerRecord> consumersByDigest,
        Map<String, McpServerRecord> mcpServicesByName, Map<UUID, RetentionConfig> retentionByTenant) {

    public RouteSnapshot {
        keys = Map.copyOf(keys);
        bindings = Map.copyOf(bindings);
        credentials = Map.copyOf(credentials);
        modelsByKeyId = immutableSets(modelsByKeyId);
        grantModelsByGrantId = immutableSets(grantModelsByGrantId);
        upstreamModelsByProductId = immutableSets(upstreamModelsByProductId);
        productCodesByProductId = Map.copyOf(productCodesByProductId);
        providerIdsByProductId = Map.copyOf(providerIdsByProductId);
        consumersByDigest = Map.copyOf(consumersByDigest);
        mcpServicesByName = Map.copyOf(mcpServicesByName);
        retentionByTenant = Map.copyOf(retentionByTenant);
    }

    private static Map<UUID, Set<String>> immutableSets(Map<UUID, Set<String>> map) {
        return map.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                e -> Collections.unmodifiableSet(Set.copyOf(e.getValue()))));
    }

    public static RouteSnapshot empty(long version, Instant loadedAt) {
        return new RouteSnapshot(version, loadedAt, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
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
     * The product's owning provider ({@code provider_products.provider_id}); null
     * when unknown. Used by request lifecycle records to keep the full identity
     * chain (tenant → user → key → product → provider → credential) without a
     * database join on the write path.
     */
    public UUID providerId(UUID productId) {
        return providerIdsByProductId.get(productId);
    }

    /**
     * A routing-relevant virtual key. Never holds secret material. {@code grantId}
     * is the key's owning grant ({@code virtual_keys.grant_id}, NOT NULL) and
     * selects its {@link #grantModels(UUID)} set; {@code userId} is the key's owner
     * ({@code virtual_keys.user_id}).
     */
    public record KeyRecord(UUID keyId, UUID tenantId, UUID userId, String publicKeyId, byte[] secretDigest,
            String cachePolicy, String purpose, UUID grantId) {

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
            EncryptedSecret encryptedSecret, Map<String, URI> baseUrlsByProtocol) {

        public CredentialRecord {
            encryptedSecret = encryptedSecret == null
                    ? null
                    : new EncryptedSecret(encryptedSecret.ciphertext(), encryptedSecret.nonce(),
                            encryptedSecret.keyVersion());
            baseUrlsByProtocol = baseUrlsByProtocol == null ? Map.of() : Map.copyOf(baseUrlsByProtocol);
        }

        /**
         * Per-protocol base URL for the route's negotiated protocol family (protocol
         * family names, e.g. OPENAI_COMPATIBLE); falls back to the single
         * {@code baseUrl} when the product has no protocol-specific templates (G3.x
         * relay wiring).
         */
        public URI baseUrl(String family) {
            URI byProtocol = baseUrlsByProtocol.get(family);
            if (byProtocol != null) {
                return byProtocol;
            }
            return baseUrl != null ? URI.create(baseUrl) : null;
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
                + productCodesByProductId.size() + ", providers=" + providerIdsByProductId.size() + "]";
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
                && productCodesByProductId.equals(that.productCodesByProductId)
                && providerIdsByProductId.equals(that.providerIdsByProductId)
                && retentionByTenant.equals(that.retentionByTenant);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(version, loadedAt, keys, bindings, credentials, modelsByKeyId,
                grantModelsByGrantId, upstreamModelsByProductId, productCodesByProductId, providerIdsByProductId,
                retentionByTenant);
    }

    /** Finds an ACTIVE consumer by its API-key digest (small set, linear scan). */
    public ConsumerRecord consumerByDigest(byte[] digestBytes) {
        for (ConsumerRecord consumer : consumersByDigest.values()) {
            if (java.security.MessageDigest.isEqual(consumer.digest(), digestBytes)) {
                return consumer;
            }
        }
        return null;
    }

    public McpServerRecord mcpService(String name) {
        return mcpServicesByName.get(name);
    }

    /** Compliance-retention switch of the tenant; null/absent = fully off. */
    public RetentionConfig retention(UUID tenantId) {
        return retentionByTenant.get(tenantId);
    }

    /**
     * One external-system consumer (Tencent doc 134890 semantics) — indexed by the
     * SHA-256 hex digest of its API key so the gateway can authenticate MCP callers
     * without storing or decrypting any secret.
     */
    public record ConsumerRecord(UUID id, UUID tenantId, String name, byte[] digest) {

        public ConsumerRecord {
            digest = digest.clone();
        }

        public byte[] digest() {
            return digest.clone();
        }
    }

    /**
     * An MCP service exposed at {@code /mcpservers/<name>/mcp} (Tencent doc
     * 135906): endpoint/transport/status plus its two-level access control (server
     * mode + per-tool overrides, Tencent doc 134890). Only rows that match the
     * loader's ACTIVE filter appear. {@code resilience} is the F12/F13 policy (V30)
     * or null when no policy row exists (everything disabled).
     */
    public record McpServerRecord(UUID id, UUID tenantId, String name, String endpoint, String transport, String status,
            String aclMode, Set<UUID> serverConsumerIds, List<McpToolRecord> tools, McpResiliencePolicy resilience) {

        public McpServerRecord {
            serverConsumerIds = Set.copyOf(serverConsumerIds);
            tools = List.copyOf(tools);
        }

        public McpToolRecord tool(String toolName) {
            for (McpToolRecord tool : tools) {
                if (tool.toolName().equals(toolName)) {
                    return tool;
                }
            }
            return null;
        }
    }

    /**
     * One tool of an MCP service; overrideMode null means inherit the server rule.
     * {@code method} is the tool's registered HTTP method (V21, default GET) used
     * by the F12 retry idempotency gate.
     */
    public record McpToolRecord(String toolName, String status, String overrideMode, Set<UUID> toolConsumerIds,
            String method) {

        public McpToolRecord {
            toolConsumerIds = Set.copyOf(toolConsumerIds);
        }
    }

}
