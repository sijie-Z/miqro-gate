package com.miqroera.miqrokey.testing;

import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;

import java.time.Instant;
import java.util.LinkedHashMap;
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

    /** Label of the primary fixture key; must equal the binding's tag. */
    public static final String PROJECT_TAG = "demo-proj";
    /** Label of the secondary fixture key. */
    public static final String OTHER_PROJECT_TAG = "other-proj";

    /** Models allowed for both fixture keys (covers the contract fixtures). */
    public static final Set<String> MODELS_ALLOWED = Set.of("demo-model", "gpt-4o-mini", "gpt-4o-mini-2024-07-18",
            "o3-mini-2025-01-31", "claude-sonnet-5-20250915");
    /** Model NOT in the fixture keys' allowlists. */
    public static final String MODEL_DENIED = "denied-model";

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

    private GatewayTestKeys() {
    }

    public static VirtualKeyCrypto crypto() {
        return CRYPTO;
    }

    /**
     * Builds the fixture snapshot: one {@code KeyRecord} per key plus its ACTIVE
     * binding, credential, and model allowlist. {@code baseUrl} is the upstream
     * base URL of every credential (the mock provider in tests).
     */
    public static RouteSnapshot snapshot(String baseUrl, KeyFixture... keys) {
        Map<String, RouteSnapshot.KeyRecord> keyMap = new LinkedHashMap<>();
        Map<UUID, RouteSnapshot.BindingRecord> bindingMap = new LinkedHashMap<>();
        Map<UUID, RouteSnapshot.CredentialRecord> credentialMap = new LinkedHashMap<>();
        Map<UUID, Set<String>> modelsMap = new LinkedHashMap<>();
        for (KeyFixture key : keys) {
            keyMap.put(key.publicKeyId(), key.keyRecord(TENANT_ID));
            bindingMap.put(key.keyId(), key.bindingRecord());
            credentialMap.put(key.credentialId(), key.credentialRecord(baseUrl));
            modelsMap.put(key.keyId(), key.models());
        }
        return new RouteSnapshot(1, Instant.EPOCH, keyMap, bindingMap, credentialMap, modelsMap);
    }

    /**
     * A self-consistent virtual key fixture: presented string (with label), public
     * key id, raw secret (caller-owned, never serialize), digest, and the snapshot
     * records that make it routable.
     */
    public record KeyFixture(String presented, String publicKeyId, byte[] rawSecret, byte[] digest, UUID keyId,
            String projectTag, UUID projectId, UUID productId, UUID credentialId, Set<String> models) {

        private static KeyFixture create(String projectTag, UUID projectId, UUID productId, UUID credentialId,
                Set<String> models) {
            VirtualKeyMaterial material = CRYPTO.generate(TENANT_ID, projectTag);
            try {
                String presented = material.fullDisplayString();
                return new KeyFixture(presented, material.publicKeyId(), material.rawSecret(), material.digest(),
                        UUID.randomUUID(), projectTag, projectId, productId, credentialId, models);
            } finally {
                material.destroy();
            }
        }

        public RouteSnapshot.KeyRecord keyRecord(UUID tenantId) {
            return new RouteSnapshot.KeyRecord(keyId, tenantId, publicKeyId, digest, "ENABLED", "chat");
        }

        public RouteSnapshot.BindingRecord bindingRecord() {
            return new RouteSnapshot.BindingRecord(keyId, projectId, projectTag, credentialId, productId);
        }

        public RouteSnapshot.CredentialRecord credentialRecord(String baseUrl) {
            return new RouteSnapshot.CredentialRecord(credentialId, TENANT_ID, productId, baseUrl, AUTH_SCHEME);
        }
    }
}
