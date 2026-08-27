package com.miqroera.miqrokey.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads the routing snapshot from the control-plane database.
 *
 * <p>
 * Runs on the refresher's scheduled executor (or the caller's thread in tests),
 * never on the Reactor event loop. Queries are plain, read-only, bounded
 * SELECTs; the full result set is small (single-tenant, ≤ 50 keys).
 * </p>
 *
 * <h2>Snapshot content</h2>
 * <ul>
 * <li>ACTIVE virtual keys (id, tenant, public key id, secret digest, cache
 * policy, purpose, owning grant) — plus ROTATING keys inside their grace
 * window.</li>
 * <li>ACTIVE label bindings joined to ACTIVE projects and ACTIVE grants
 * (DISTINCT ON virtual_key_id).</li>
 * <li>ACTIVE upstream credentials reachable from any grant, with their
 * product's base URL templates, auth scheme (jsonb), and the ACTIVE version's
 * ciphertext (encrypted_secret, nonce, encryption_key_version) — decryption
 * happens in memory on the hot path, never here.</li>
 * <li>Per-key model grants (virtual_key_models).</li>
 * <li>Per-grant model grants of ACTIVE grants (project_provider_grant_models)
 * and per-product upstream models (model_catalog, ACTIVE rows only) — the
 * {@code /v1/models} authorization inputs.</li>
 * <li>Product codes (provider_products.product_code) so the gateway can gate
 * products against the signed provider catalog.</li>
 * <li>Owning provider ids (provider_products.provider_id) for request lifecycle
 * records.</li>
 * </ul>
 */
public final class JdbcRouteSnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(JdbcRouteSnapshotLoader.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRouteSnapshotLoader(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Loads a fresh snapshot. The version is taken from the caller's counter so the
     * holder can detect regression.
     */
    public RouteSnapshot load(long version, Instant loadedAt) {
        Map<String, RouteSnapshot.KeyRecord> keys = loadKeys();
        Map<UUID, RouteSnapshot.BindingRecord> bindings = loadBindings();
        Map<UUID, RouteSnapshot.CredentialRecord> credentials = loadCredentials();
        Map<UUID, Set<String>> models = loadModels();
        Map<UUID, Set<String>> grantModels = loadGrantModels();
        Map<UUID, Set<String>> upstreamModels = loadUpstreamModels();
        ProductIds productIds = loadProductIds();
        return new RouteSnapshot(version, loadedAt, keys, bindings, credentials, models, grantModels, upstreamModels,
                productIds.productCodes(), productIds.providerIds());
    }

    private Map<String, RouteSnapshot.KeyRecord> loadKeys() {
        Map<String, RouteSnapshot.KeyRecord> keys = new HashMap<>();
        // ROTATING keys stay routable until their grace window expires
        // (revoked_at), so a rotated key keeps working while the replacement
        // propagates through the snapshot.
        jdbc.query("""
                SELECT id, tenant_id, user_id, public_key_id, secret_digest, cache_policy, purpose, grant_id
                FROM virtual_keys
                WHERE status = 'ACTIVE'
                   OR (status = 'ROTATING' AND revoked_at > now())
                """, rs -> {
            RouteSnapshot.KeyRecord key = new RouteSnapshot.KeyRecord((UUID) rs.getObject("id"),
                    (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("user_id"), rs.getString("public_key_id"),
                    rs.getBytes("secret_digest"), rs.getString("cache_policy"), rs.getString("purpose"),
                    (UUID) rs.getObject("grant_id"));
            keys.put(key.publicKeyId(), key);
        });
        return keys;
    }

    private Map<UUID, RouteSnapshot.BindingRecord> loadBindings() {
        Map<UUID, RouteSnapshot.BindingRecord> bindings = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (b.virtual_key_id)
                       b.virtual_key_id, b.project_id, p.project_tag, g.upstream_credential_id, g.provider_product_id
                FROM key_project_binding b
                JOIN projects p ON p.id = b.project_id AND p.tenant_id = b.tenant_id
                -- The binding's grant is authoritative: a project may hold
                -- several ACTIVE grants (different products/credentials), and
                -- the key must route only to the grant it was authorized for,
                -- never to a sibling grant of the same project.
                JOIN virtual_keys vk ON vk.id = b.virtual_key_id AND vk.tenant_id = b.tenant_id
                JOIN project_provider_grants g ON g.id = vk.grant_id
                                              AND g.project_id = b.project_id
                                              AND g.tenant_id = b.tenant_id
                                              AND g.status = 'ACTIVE'
                WHERE b.status = 'ACTIVE' AND p.status = 'ACTIVE'
                ORDER BY b.virtual_key_id, b.created_at
                """, rs -> {
            UUID keyId = (UUID) rs.getObject("virtual_key_id");
            RouteSnapshot.BindingRecord binding = new RouteSnapshot.BindingRecord(keyId,
                    (UUID) rs.getObject("project_id"), rs.getString("project_tag"),
                    (UUID) rs.getObject("upstream_credential_id"), (UUID) rs.getObject("provider_product_id"));
            bindings.put(keyId, binding);
        });
        return bindings;
    }

    private Map<UUID, RouteSnapshot.CredentialRecord> loadCredentials() {
        Map<UUID, RouteSnapshot.CredentialRecord> credentials = new HashMap<>();
        // The ACTIVE version's ciphertext is loaded at refresh time so the hot
        // path decrypts in memory and never queries the database. The partial
        // unique index uq_credential_versions_one_active guarantees at most one
        // ACTIVE version per credential, so the join cannot duplicate rows.
        jdbc.query("""
                SELECT c.id AS credential_id, c.tenant_id, pp.id AS product_id,
                       pp.base_url_templates, pp.auth_scheme,
                       v.encrypted_secret, v.nonce, v.encryption_key_version
                FROM upstream_credentials c
                JOIN upstream_subscriptions s ON s.tenant_id = c.tenant_id AND s.id = c.subscription_id
                JOIN provider_products pp ON pp.id = s.provider_product_id
                LEFT JOIN upstream_credential_versions v
                       ON v.tenant_id = c.tenant_id AND v.id = c.active_version_id AND v.status = 'ACTIVE'
                WHERE c.status = 'ACTIVE'
                  AND c.id IN (SELECT g.upstream_credential_id FROM project_provider_grants g WHERE g.status = 'ACTIVE')
                """, rs -> {
            UUID credentialId = (UUID) rs.getObject("credential_id");
            BaseUrlSet baseUrls = parseBaseUrls(rs.getString("base_url_templates"));
            if (baseUrls.single() == null && baseUrls.byProtocolUris().isEmpty()) {
                log.warn("Credential {} has no usable base_url_templates entry; excluded from routing snapshot",
                        credentialId);
                return;
            }
            EncryptedSecret encryptedSecret = null;
            byte[] ciphertext = rs.getBytes("encrypted_secret");
            if (ciphertext != null) {
                encryptedSecret = new EncryptedSecret(ciphertext, rs.getBytes("nonce"),
                        rs.getString("encryption_key_version"));
            }
            RouteSnapshot.CredentialRecord credential = new RouteSnapshot.CredentialRecord(credentialId,
                    (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("product_id"), baseUrls.single(),
                    rs.getString("auth_scheme"), encryptedSecret, baseUrls.byProtocolUris());
            credentials.put(credentialId, credential);
        });
        return credentials;
    }

    private Map<UUID, Set<String>> loadModels() {
        Map<UUID, Set<String>> models = new HashMap<>();
        jdbc.query("""
                SELECT virtual_key_id, model_id FROM virtual_key_models
                """, rs -> {
            UUID keyId = (UUID) rs.getObject("virtual_key_id");
            models.computeIfAbsent(keyId, k -> new java.util.HashSet<>()).add(rs.getString("model_id"));
        });
        models.replaceAll((k, v) -> Set.copyOf(v));
        return models;
    }

    /**
     * Per-grant model grants. Rows of revoked grants are excluded — a grant's model
     * permissions die with it even if the junction rows linger.
     */
    private Map<UUID, Set<String>> loadGrantModels() {
        Map<UUID, Set<String>> grantModels = new HashMap<>();
        jdbc.query("""
                SELECT gm.grant_id, gm.model_id
                FROM project_provider_grant_models gm
                JOIN project_provider_grants g
                  ON g.id = gm.grant_id AND g.tenant_id = gm.tenant_id AND g.status = 'ACTIVE'
                """, rs -> {
            UUID grantId = (UUID) rs.getObject("grant_id");
            grantModels.computeIfAbsent(grantId, k -> new java.util.HashSet<>()).add(rs.getString("model_id"));
        });
        grantModels.replaceAll((k, v) -> Set.copyOf(v));
        return grantModels;
    }

    /**
     * Upstream models per product ({@code model_catalog}, ACTIVE rows only). The
     * control plane writes these rows exclusively from successful official-API
     * fetches, so the set is the last successful catalog — a failed fetch never
     * mutates it.
     */
    private Map<UUID, Set<String>> loadUpstreamModels() {
        Map<UUID, Set<String>> upstreamModels = new HashMap<>();
        jdbc.query("""
                SELECT provider_product_id, model_id
                FROM model_catalog
                WHERE status = 'ACTIVE'
                """, rs -> {
            UUID productId = (UUID) rs.getObject("provider_product_id");
            upstreamModels.computeIfAbsent(productId, k -> new java.util.HashSet<>()).add(rs.getString("model_id"));
        });
        upstreamModels.replaceAll((k, v) -> Set.copyOf(v));
        return upstreamModels;
    }

    /**
     * Product codes ({@code provider_products.product_code}) so the gateway can
     * gate products against the signed catalog, and owning provider ids
     * ({@code provider_products.provider_id}) so request lifecycle records keep the
     * full identity chain without a join on the write path.
     */
    private ProductIds loadProductIds() {
        Map<UUID, String> productCodes = new HashMap<>();
        Map<UUID, UUID> providerIds = new HashMap<>();
        jdbc.query("""
                SELECT id, product_code, provider_id FROM provider_products
                """, rs -> {
            UUID productId = (UUID) rs.getObject("id");
            productCodes.put(productId, rs.getString("product_code"));
            providerIds.put(productId, (UUID) rs.getObject("provider_id"));
        });
        return new ProductIds(productCodes, providerIds);
    }

    private record ProductIds(Map<UUID, String> productCodes, Map<UUID, UUID> providerIds) {
    }

    /**
     * Extracts the first usable base URL from the product's jsonb
     * {@code base_url_templates} array. Unknown structure returns null.
     */
    /**
     * Parses {@code base_url_templates}: entries may carry an optional
     * {@code protocols} array (per-protocol bases, G3.x relay wiring) or no
     * protocol (single/base fallback). Unknown structure yields an empty set.
     */
    private BaseUrlSet parseBaseUrls(String json) {
        if (json == null || json.isBlank()) {
            return BaseUrlSet.EMPTY;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String single = null;
            Map<String, String> byProtocol = new LinkedHashMap<>();
            if (node.isArray()) {
                for (JsonNode entry : node) {
                    if (entry.isTextual()) {
                        if (single == null) {
                            single = entry.asText();
                        }
                        continue;
                    }
                    String url = entry.path("url").asText(null);
                    if (url == null || url.isBlank()) {
                        continue;
                    }
                    JsonNode protocols = entry.path("protocols");
                    if (protocols.isArray() && !protocols.isEmpty()) {
                        for (JsonNode protocol : protocols) {
                            byProtocol.putIfAbsent(protocol.asText(), url);
                        }
                    } else if (single == null) {
                        single = url;
                    }
                }
            } else if (node.isObject()) {
                String url = node.path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    single = url;
                }
            } else if (node.isTextual() && !node.asText().isBlank()) {
                single = node.asText();
            }
            return new BaseUrlSet(single, byProtocol);
        } catch (Exception e) {
            log.warn("Cannot parse base_url_templates jsonb: {}", e.getMessage());
            return BaseUrlSet.EMPTY;
        }
    }

    /** Parsed base URL set: a single fallback plus per-protocol entries. */
    record BaseUrlSet(String single, Map<String, String> byProtocol) {

        static final BaseUrlSet EMPTY = new BaseUrlSet(null, Map.of());

        Map<String, URI> byProtocolUris() {
            Map<String, URI> result = new LinkedHashMap<>();
            byProtocol.forEach((protocol, url) -> result.put(protocol, URI.create(url)));
            return result;
        }
    }
}
