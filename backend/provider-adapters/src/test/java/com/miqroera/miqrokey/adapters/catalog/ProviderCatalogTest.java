package com.miqroera.miqrokey.adapters.catalog;

import com.miqroera.miqrokey.spi.AdapterStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProviderCatalog load, verify, lookup")
class ProviderCatalogTest {

    @Test
    @DisplayName("built-in catalog loads, verifies and exposes all P0 products")
    void builtInCatalogLoads() {
        ProviderCatalog catalog = ProviderCatalog.loadBuiltIn();
        assertThat(catalog.definitions()).hasSize(23);
        assertThat(catalog.definitions().stream().map(d -> d.vendor()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("tencent", "aliyun", "zhipu", "minimax", "moonshot", "baidu", "volcengine",
                        "deepseek");
        // Every entry is data-only and carries an https base URL.
        assertThat(catalog.definitions()).allSatisfy(d -> {
            assertThat(d.baseUrlTemplate().getScheme()).isEqualTo("https");
            assertThat(d.status()).isEqualTo(AdapterStatus.DOCUMENTED);
            assertThat(d.adapterId()).isNotBlank();
        });
        assertThat(catalog.findById("deepseek-payg-api")).isPresent();
        assertThat(catalog.findById("tencent-token-plan-enterprise-pro")).isPresent();
        assertThat(catalog.findById("unknown-product")).isEmpty();
    }

    @Test
    @DisplayName("tampered manifest bytes fail to load with CatalogLoadException")
    void tamperedManifestRejected() {
        KeyPair keys = TestCatalogSigner.newKeyPair();
        byte[] data = TestCatalogSigner.validManifest().getBytes(StandardCharsets.UTF_8);
        byte[] signature = TestCatalogSigner.sign(keys.getPrivate(), data);
        // Flip one byte in the manifest: re-encoding that changes no data still
        // invalidates the signature, and so does any real change.
        byte[] tampered = data.clone();
        tampered[tampered.length - 10] ^= 0x01;
        assertThatThrownBy(() -> ProviderCatalog.fromBytes(tampered, signature, keys.getPublic()))
                .isInstanceOf(CatalogLoadException.class).hasMessageContaining("signature");
    }

    @Test
    @DisplayName("schema-violating manifest with a valid signature fails schema validation")
    void schemaViolationRejected() {
        KeyPair keys = TestCatalogSigner.newKeyPair();
        String manifest = TestCatalogSigner.validManifest().replace("\"status\": \"DOCUMENTED\"",
                "\"status\": \"DOCUMENTED\",\n      \"executable\": \"evil\"");
        byte[] signature = TestCatalogSigner.sign(keys.getPrivate(), manifest.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(
                () -> ProviderCatalog.fromBytes(manifest.getBytes(StandardCharsets.UTF_8), signature, keys.getPublic()))
                .isInstanceOf(CatalogLoadException.class).hasMessageContaining("unknown field");
    }

    @Test
    @DisplayName("signature produced by a different key fails to load")
    void wrongKeyRejected() {
        KeyPair signerKeys = TestCatalogSigner.newKeyPair();
        KeyPair verifierKeys = TestCatalogSigner.newKeyPair();
        byte[] data = TestCatalogSigner.validManifest().getBytes(StandardCharsets.UTF_8);
        byte[] signature = TestCatalogSigner.sign(signerKeys.getPrivate(), data);
        assertThatThrownBy(() -> ProviderCatalog.fromBytes(data, signature, verifierKeys.getPublic()))
                .isInstanceOf(CatalogLoadException.class).hasMessageContaining("signature");
    }

    @Test
    @DisplayName("catalog data cannot reference adapter code: resolution is a registry lookup by adapterId only")
    void catalogCannotLoadAdapterCode() {
        // A valid, correctly signed catalog whose adapterId is not registered.
        // Validation succeeds (the catalog is data), but the runtime adapter
        // resolution — which happens against the compile-time registry — yields
        // nothing. No class loading, no plugin discovery, no code fields.
        KeyPair keys = TestCatalogSigner.newKeyPair();
        byte[] data = TestCatalogSigner.validManifest().getBytes(StandardCharsets.UTF_8);
        byte[] signature = TestCatalogSigner.sign(keys.getPrivate(), data);
        ProviderCatalog catalog = ProviderCatalog.fromBytes(data, signature, keys.getPublic());

        com.miqroera.miqrokey.adapters.registry.BuiltInAdapterRegistry registry = new com.miqroera.miqrokey.adapters.registry.BuiltInAdapterRegistry();
        Set<String> registered = registry.adapterIds();
        assertThat(catalog.definitions()).allSatisfy(d -> {
            // No definition can ever cause code to be loaded: the only way an
            // adapter comes into existence is registration in the compile-time
            // registry, keyed by adapterId.
            assertThat(d.adapterId()).isNotBlank();
            assertThat(registered).doesNotContain(d.adapterId());
        });
    }
}
