package com.miqroera.miqrokey.gateway.vkey;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyRing;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyMaterial;
import com.miqroera.miqrokey.domain.crypto.impl.HmacVirtualKeyProvider;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0018 contract: one key core (same HMAC digest) presented with DIFFERENT
 * project labels resolves to DIFFERENT bindings — each with its own project,
 * credential and grant. A label with no binding, a tampered core, and a
 * malformed header all fail uniformly ({@code VIRTUAL_KEY_INVALID}).
 */
@Tag("unit")
@DisplayName("VirtualKeyResolver — one key, several project labels (ADR-0018)")
class VirtualKeyResolverTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID GRANT_A = UUID.randomUUID();
    private static final UUID GRANT_B = UUID.randomUUID();
    private static final UUID CRED_A = UUID.randomUUID();
    private static final UUID CRED_B = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();

    private static final byte[] HMAC_KEY = buildKey();

    private static byte[] buildKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (0x5A + i);
        }
        return key;
    }

    @Test
    @DisplayName("the presented suffix selects the binding: same core, different projects")
    void suffixSelectsBinding() {
        VirtualKeyCrypto crypto = new HmacVirtualKeyProvider(new KeyRing("v1", Map.of("v1", HMAC_KEY)));
        VirtualKeyMaterial material = crypto.generate(TENANT, "tag-a");
        try {
            String core = material.fullDisplayString().substring(0, material.fullDisplayString().lastIndexOf('.'));
            String presentedA = core + ".tag-a";
            String presentedB = core + ".tag-b";

            RouteSnapshot snapshot = snapshot(crypto, material);
            VirtualKeyResolver resolver = new VirtualKeyResolver(() -> snapshot, providerOf(crypto),
                    new RequestContextResolver());

            AuthContext ctxA = resolver.resolve(bearer(presentedA));
            AuthContext ctxB = resolver.resolve(bearer(presentedB));

            assertThat(ctxA.binding().projectId()).isEqualTo(PROJECT_A);
            assertThat(ctxA.binding().credentialId()).isEqualTo(CRED_A);
            assertThat(ctxA.binding().grantId()).isEqualTo(GRANT_A);
            assertThat(ctxB.binding().projectId()).isEqualTo(PROJECT_B);
            assertThat(ctxB.binding().credentialId()).isEqualTo(CRED_B);
            assertThat(ctxB.binding().grantId()).isEqualTo(GRANT_B);
        } finally {
            material.destroy();
        }
    }

    @Test
    @DisplayName("an unbound or foreign label fails uniformly")
    void unboundLabelFails() {
        VirtualKeyCrypto crypto = new HmacVirtualKeyProvider(new KeyRing("v1", Map.of("v1", HMAC_KEY)));
        VirtualKeyMaterial material = crypto.generate(TENANT, "tag-a");
        try {
            String core = material.fullDisplayString().substring(0, material.fullDisplayString().lastIndexOf('.'));
            RouteSnapshot snapshot = snapshot(crypto, material);
            VirtualKeyResolver resolver = new VirtualKeyResolver(() -> snapshot, providerOf(crypto),
                    new RequestContextResolver());

            assertThatThrownBy(() -> resolver.resolve(bearer(core + ".tag-c")))
                    .isInstanceOf(AuthFailureException.class);
            // A tampered core must not validate, even with a bound label.
            String tampered = core.substring(0, core.length() - 1) + (core.endsWith("A") ? "B" : "A") + ".tag-a";
            assertThatThrownBy(() -> resolver.resolve(bearer(tampered))).isInstanceOf(AuthFailureException.class);
            assertThatThrownBy(() -> resolver.resolve(MockServerHttpRequest.get("/v1/models").build()))
                    .isInstanceOf(AuthFailureException.class);
        } finally {
            material.destroy();
        }
    }

    // ------------------------------------------------------------------

    private static RouteSnapshot snapshot(VirtualKeyCrypto crypto, VirtualKeyMaterial material) {
        RouteSnapshot.KeyRecord key = new RouteSnapshot.KeyRecord(KEY_ID, TENANT, USER, material.publicKeyId(),
                material.digest(), "DISABLED", "CLAUDE_CODE", GRANT_A);
        RouteSnapshot.BindingRecord bindingA = new RouteSnapshot.BindingRecord(KEY_ID, PROJECT_A, "tag-a", CRED_A,
                PRODUCT, GRANT_A);
        RouteSnapshot.BindingRecord bindingB = new RouteSnapshot.BindingRecord(KEY_ID, PROJECT_B, "tag-b", CRED_B,
                PRODUCT, GRANT_B);
        RouteSnapshot.CredentialRecord credA = credential(CRED_A);
        RouteSnapshot.CredentialRecord credB = credential(CRED_B);
        return new RouteSnapshot(1, Instant.EPOCH, Map.of(material.publicKeyId(), key),
                Map.of(KEY_ID, Map.of("tag-a", bindingA, "tag-b", bindingB)), Map.of(CRED_A, credA, CRED_B, credB),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                java.util.Map.of(), java.util.Map.of());
    }

    private static RouteSnapshot.CredentialRecord credential(UUID id) {
        return new RouteSnapshot.CredentialRecord(id, TENANT, PRODUCT, "https://api.test.example",
                "{\"type\":\"bearer\"}", new EncryptedSecret(new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, "v1"), Map.of());
    }

    private static MockServerHttpRequest bearer(String presented) {
        return MockServerHttpRequest.get("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + presented).build();
    }

    private static ObjectProvider<VirtualKeyCrypto> providerOf(VirtualKeyCrypto crypto) {
        return new ObjectProvider<>() {
            @Override
            public VirtualKeyCrypto getObject() {
                return crypto;
            }

            @Override
            public VirtualKeyCrypto getObject(Object... args) {
                return crypto;
            }

            @Override
            public VirtualKeyCrypto getIfAvailable() {
                return crypto;
            }

            @Override
            public VirtualKeyCrypto getIfUnique() {
                return crypto;
            }

            @Override
            public Stream<VirtualKeyCrypto> stream() {
                return Stream.of(crypto);
            }
        };
    }
}
