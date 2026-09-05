package com.miqroera.miqrokey.testing;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gateway auth fixtures for contract tests.
 *
 * <p>
 * Keys are generated once per JVM against a FIXED HMAC key ring and fixed
 * tenant/project ids, so every fixture — presented key string, public key id,
 * raw secret, HMAC digest — is internally consistent: the presented key passes
 * {@code VirtualKeyParser} and validates against the digest stored in the
 * fixture snapshot.
 * </p>
 *
 * <p>
 * Each fixture also carries the three additional {@code /v1/models}
 * authorization inputs: grant models ({@code project_provider_grant_models}),
 * upstream models ({@code model_catalog}) and the product code (the signed
 * catalog gate). The happy-path fixtures align all layers with
 * {@link #MODELS_ALLOWED}; the negative fixtures break one layer so the
 * intersection shrinks.
 * </p>
 *
 * <p>
 * The raw secret and full presented key are secret material. Never log them and
 * never assert on their contents in failure output.
 * </p>
 */
public final class GatewayTestKeys {

    public static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID PROJECT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID OTHER_PROJECT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    public static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    public static final UUID CREDENTIAL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    public static final UUID OTHER_CREDENTIAL_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    /**
     * Label of the primary fixture key; must equal the binding's tag.
     */
    public static final String PROJECT_TAG = "demo-proj";
    /** Label of the secondary fixture key. */
    public static final String OTHER_PROJECT_TAG = "other-proj";

    /**
     * Product code of the fixture products — a real id of the signed provider
     * catalog ({@code deepseek-payg-api}), so the catalog gate passes.
     */
    public static final String PRODUCT_CODE = "deepseek-payg-api";
    /** Product code that does NOT exist in the signed catalog. */
    public static final String UNKNOWN_PRODUCT_CODE = "ghost-product";

    /** Models allowed for both fixture keys (covers the contract fixtures). */
    public static final Set<String> MODELS_ALLOWED = Set.of("demo-model", "gpt-4o-mini", "gpt-4o-mini-2024-07-18",
            "o3-mini-2025-01-31", "claude-sonnet-5-20250915");
    /** Model NOT in the fixture keys' allowlists. */
    public static final String MODEL_DENIED = "denied-model";
    /** Model present in the key's models but NOT in the grant's models. */
    public static final String MODEL_GRANT_DENIED = "gpt-4o-mini";
    /** Model present in the key's models but NOT in the upstream models. */
    public static final String MODEL_UPSTREAM_DENIED = "o3-mini-2025-01-31";

    /** Product catalog auth scheme (jsonb text) carried by the snapshot. */
    public static final String AUTH_SCHEME = "{\"type\":\"bearer\",\"header\":\"authorization\"}";

    private static final byte[] HMAC_KEY = new byte[32];
    static {
        for (int i = 0; i < HMAC_KEY.length; i++) {
            HMAC_KEY[i] = (byte) (0x5A + i);
        }
    }

    private static final VirtualKeyCrypto CRYPTO = new HmacVirtualKeyProvider(
            new KeyRing("v1", Map.of("v1", HMAC_KEY)));

    /** Key bound to {@link #PROJECT_TAG} with the fixture model allowlist. */
    public static final KeyFixture DEFAULT_KEY = KeyFixture.create(PROJECT_TAG, PROJECT_ID, PRODUCT_ID, CREDENTIAL_ID,
            MODELS_ALLOWED);

    /** Second key, different tag and credential — negative-path tests. */
    public static final KeyFixture OTHER_KEY = KeyFixture.create(OTHER_PROJECT_TAG, OTHER_PROJECT_ID, PRODUCT_ID,
            OTHER_CREDENTIAL_ID, MODELS_ALLOWED);

    /** Well-formed key that does NOT exist in the fixture snapshot. */
    public static final KeyFixture UNKNOWN_KEY = KeyFixture.create("ghost-proj", UUID.randomUUID(), PRODUCT_ID,
            UUID.randomUUID(), MODELS_ALLOWED);

    /**
     * Key whose grant excludes {@link #MODEL_GRANT_DENIED} — the model is in the
     * key's models but not authorized at the grant layer.
     */
    public static final KeyFixture GRANT_LIMITED_KEY = KeyFixture.create("grant-limited", UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), MODELS_ALLOWED,
            MODELS_ALLOWED.stream().filter(m -> !m.equals(MODEL_GRANT_DENIED))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()),
            MODELS_ALLOWED, PRODUCT_CODE);

    /**
     * Key whose product's upstream catalog excludes {@link #MODEL_UPSTREAM_DENIED}
     * — the model is in the key's models but has never been seen by a successful
     * official-API fetch.
     */
    public static final KeyFixture UPSTREAM_LIMITED_KEY = KeyFixture.create("upstream-limited", UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), MODELS_ALLOWED, MODELS_ALLOWED,
            MODELS_ALLOWED.stream().filter(m -> !m.equals(MODEL_UPSTREAM_DENIED))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()),
            PRODUCT_CODE);

    /**
     * Key whose product has no upstream models yet (no successful fetch ever
     * happened): the strict intersection is empty.
     */
    public static final KeyFixture NO_UPSTREAM_KEY = KeyFixture.create("no-upstream", UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), MODELS_ALLOWED, MODELS_ALLOWED, Set.of(), PRODUCT_CODE);

    /**
     * Key whose product code is unknown to the signed provider catalog: nothing is
     * served — the catalog gate is the outer authorization boundary.
     */
    public static final KeyFixture UNKNOWN_PRODUCT_KEY = KeyFixture.create("ghost-product", UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), MODELS_ALLOWED, MODELS_ALLOWED, MODELS_ALLOWED, UNKNOWN_PRODUCT_CODE);

    private GatewayTestKeys() {
    }

    public static VirtualKeyCrypto crypto() {
        return CRYPTO;
    }

    /**
     * Builds the fixture snapshot: one {@code KeyRecord} per key plus its ACTIVE
     * binding, credential, model allowlist, grant models, upstream models and
     * product code. {@code baseUrl} is the upstream base URL of every credential
     * (the mock provider in tests).
     * <p>
     * Every snapshot also carries the MCP proxy fixtures (consumers by digest + the
     * open/gated services under {@code baseUrl}/mcp); they are inert unless a test
     * calls {@code /mcpservers/<name>/mcp}.
     * </p>
     */
    public static RouteSnapshot snapshot(String baseUrl, KeyFixture... keys) {
        return snapshotWithResilience(baseUrl, Map.of(), keys);
    }

    /**
     * Fixture snapshot with per-service resilience policies (F12/F13, V30)
     * overrides keyed by service name; absent services stay fully disabled.
     */
    public static RouteSnapshot snapshotWithResilience(String baseUrl, Map<String, McpResiliencePolicy> policies,
            KeyFixture... keys) {
        Map<String, RouteSnapshot.KeyRecord> keyMap = new LinkedHashMap<>();
        Map<UUID, RouteSnapshot.BindingRecord> bindingMap = new LinkedHashMap<>();
        Map<UUID, RouteSnapshot.CredentialRecord> credentialMap = new LinkedHashMap<>();
        Map<UUID, Set<String>> modelsMap = new LinkedHashMap<>();
        Map<UUID, Set<String>> grantModelsMap = new LinkedHashMap<>();
        Map<UUID, Set<String>> upstreamModelsMap = new LinkedHashMap<>();
        Map<UUID, String> productCodesMap = new LinkedHashMap<>();
        Map<UUID, UUID> providerIdsMap = new LinkedHashMap<>();
        for (KeyFixture key : keys) {
            keyMap.put(key.publicKeyId(), key.keyRecord(TENANT_ID));
            bindingMap.put(key.keyId(), key.bindingRecord());
            credentialMap.put(key.credentialId(), key.credentialRecord(baseUrl));
            modelsMap.put(key.keyId(), key.models());
            grantModelsMap.put(key.grantId(), key.grantModels());
            upstreamModelsMap.put(key.productId(), key.upstreamModels());
            productCodesMap.put(key.productId(), key.productCode());
            // Provider identity belongs to the product, not the key: several
            // fixture keys share PRODUCT_ID, so the first key to claim a product
            // defines its provider (production rows are 1:1 anyway).
            providerIdsMap.putIfAbsent(key.productId(), key.providerId());
        }
        return new RouteSnapshot(1, Instant.EPOCH, keyMap, bindingMap, credentialMap, modelsMap, grantModelsMap,
                upstreamModelsMap, productCodesMap, providerIdsMap, mcpConsumers(), mcpServices(baseUrl, policies),
                Map.of());
    }

    // ------------------------------------------------------------------
    // MCP proxy fixtures (F01 contract tests; see McpProxyContractTest)
    // ------------------------------------------------------------------

    /** Service with server ACL mode NONE — any consumer may call it. */
    public static final String MCP_OPEN_SERVICE = "open-demo";
    /** Service with server ACL mode ALLOW (MCP_ALLOWED + MCP_SERVER_ONLY). */
    public static final String MCP_GATED_SERVICE = "gated-demo";
    /** Enabled tool on {@link #MCP_OPEN_SERVICE} (inherits server rule). */
    public static final String MCP_TOOL_ECHO = "echo-tool";
    /** Disabled tool on {@link #MCP_OPEN_SERVICE}. */
    public static final String MCP_TOOL_LEGACY = "legacy-tool";
    /** Enabled tool on {@link #MCP_GATED_SERVICE} (inherits server rule). */
    public static final String MCP_TOOL_SHARED = "shared-tool";
    /** Enabled tool on {@link #MCP_GATED_SERVICE} with an ALLOW override. */
    public static final String MCP_TOOL_RESTRICTED = "restricted-tool";
    /** Disabled tool on {@link #MCP_GATED_SERVICE}. */
    public static final String MCP_TOOL_QUIET = "quiet-tool";

    /** One API-consumer fixture: self-consistent presented key + digest. */
    public record ConsumerFixture(UUID id, String name, String presentedKey) {
        public byte[] digest() {
            return sha256(presentedKey);
        }
    }

    /** On the gated service's server list and restricted-tool ALLOW list. */
    public static final ConsumerFixture MCP_ALLOWED = consumer("allowed");
    /** On the gated service's server list only (tool override must deny). */
    public static final ConsumerFixture MCP_SERVER_ONLY = consumer("server-only");
    /** On no list at all. */
    public static final ConsumerFixture MCP_OUTSIDER = consumer("outsider");

    private static ConsumerFixture consumer(String label) {
        String name = "drill-" + label;
        return new ConsumerFixture(UUID.nameUUIDFromBytes(("mqk-consumer-" + name).getBytes(StandardCharsets.UTF_8)),
                name, "mqk_api_drill_" + label + "_" + UUID.randomUUID());
    }

    private static Map<String, RouteSnapshot.ConsumerRecord> mcpConsumers() {
        Map<String, RouteSnapshot.ConsumerRecord> consumers = new LinkedHashMap<>();
        for (ConsumerFixture fixture : List.of(MCP_ALLOWED, MCP_SERVER_ONLY, MCP_OUTSIDER)) {
            consumers.putIfAbsent(fixture.id().toString(),
                    new RouteSnapshot.ConsumerRecord(fixture.id(), TENANT_ID, fixture.name(), fixture.digest()));
        }
        return consumers;
    }

    private static Map<String, RouteSnapshot.McpServerRecord> mcpServices(String baseUrl,
            Map<String, McpResiliencePolicy> policies) {
        String endpoint = baseUrl + "/mcp";
        RouteSnapshot.McpServerRecord open = new RouteSnapshot.McpServerRecord(serviceId(MCP_OPEN_SERVICE), TENANT_ID,
                MCP_OPEN_SERVICE, endpoint, "STREAMABLE_HTTP", "ONLINE", "NONE", Set.of(),
                List.of(tool(MCP_TOOL_ECHO, "ENABLED", null, Set.of(), "GET"),
                        tool(MCP_TOOL_LEGACY, "DISABLED", null, Set.of(), "GET")),
                policies.get(MCP_OPEN_SERVICE));
        RouteSnapshot.McpServerRecord gated = new RouteSnapshot.McpServerRecord(serviceId(MCP_GATED_SERVICE), TENANT_ID,
                MCP_GATED_SERVICE, endpoint, "STREAMABLE_HTTP", "ONLINE", "ALLOW",
                Set.of(MCP_ALLOWED.id(), MCP_SERVER_ONLY.id()),
                List.of(tool(MCP_TOOL_SHARED, "ENABLED", null, Set.of(), "GET"),
                        tool(MCP_TOOL_RESTRICTED, "ENABLED", "ALLOW", Set.of(MCP_ALLOWED.id()), "POST"),
                        tool(MCP_TOOL_QUIET, "DISABLED", null, Set.of(), "GET")),
                policies.get(MCP_GATED_SERVICE));
        Map<String, RouteSnapshot.McpServerRecord> services = new LinkedHashMap<>();
        services.put(MCP_OPEN_SERVICE, open);
        services.put(MCP_GATED_SERVICE, gated);
        return services;
    }

    private static UUID serviceId(String name) {
        return UUID.nameUUIDFromBytes(("mqk-mcp-service-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static RouteSnapshot.McpToolRecord tool(String name, String status, String overrideMode, Set<UUID> allowed,
            String method) {
        return new RouteSnapshot.McpToolRecord(name, status, overrideMode, allowed, method);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * A self-consistent virtual key fixture: presented string (with label), public
     * key id, raw secret (caller-owned, never serialize), digest, the snapshot
     * records that make it routable, and the {@code /v1/models} authorization
     * inputs (grant models, upstream models, product code).
     */
    public record KeyFixture(String presented, String publicKeyId, byte[] rawSecret, byte[] digest, UUID keyId,
            String projectTag, UUID projectId, UUID productId, UUID credentialId, Set<String> models, UUID grantId,
            String productCode, Set<String> grantModels, Set<String> upstreamModels, UUID userId, UUID providerId) {

        /**
         * Happy-path fixture: all authorization layers allow {@code models}.
         */
        private static KeyFixture create(String projectTag, UUID projectId, UUID productId, UUID credentialId,
                Set<String> models) {
            return create(projectTag, projectId, productId, credentialId, models, models, models, PRODUCT_CODE);
        }

        private static KeyFixture create(String projectTag, UUID projectId, UUID productId, UUID credentialId,
                Set<String> models, Set<String> grantModels, Set<String> upstreamModels, String productCode) {
            VirtualKeyMaterial material = CRYPTO.generate(TENANT_ID, projectTag);
            try {
                String presented = material.fullDisplayString();
                return new KeyFixture(presented, material.publicKeyId(), material.rawSecret(), material.digest(),
                        UUID.randomUUID(), projectTag, projectId, productId, credentialId, Set.copyOf(models),
                        UUID.randomUUID(), productCode, Set.copyOf(grantModels), Set.copyOf(upstreamModels),
                        UUID.randomUUID(), UUID.randomUUID());
            } finally {
                material.destroy();
            }
        }

        public RouteSnapshot.KeyRecord keyRecord(UUID tenantId) {
            return new RouteSnapshot.KeyRecord(keyId, tenantId, userId, publicKeyId, digest, "ENABLED", "chat",
                    grantId);
        }

        public RouteSnapshot.BindingRecord bindingRecord() {
            return new RouteSnapshot.BindingRecord(keyId, projectId, projectTag, credentialId, productId);
        }

        public RouteSnapshot.CredentialRecord credentialRecord(String baseUrl) {
            // Synthetic ciphertext/nonce (the fixture injector never decrypts):
            // the snapshot contract requires the ACTIVE version's EncryptedSecret.
            return new RouteSnapshot.CredentialRecord(credentialId, TENANT_ID, productId, baseUrl, AUTH_SCHEME,
                    new EncryptedSecret(new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, "v1"), java.util.Map.of());
        }
    }
}
