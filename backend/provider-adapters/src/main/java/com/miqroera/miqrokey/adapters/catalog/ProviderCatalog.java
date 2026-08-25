package com.miqroera.miqrokey.adapters.catalog;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.miqroera.miqrokey.spi.ProviderProductDefinition;

/**
 * Loads and serves the built-in provider catalog: reads the versioned manifest
 * from the classpath, verifies its Ed25519 signature against the bundled public
 * key, validates the schema, and exposes immutable product definitions.
 *
 * <p>
 * The catalog is pure data. Adapter resolution is a separate step that only
 * ever consults the compile-time {@code AdapterRegistry} by {@code adapterId};
 * nothing in the manifest can reference or load code
 * ({@code docs/provider-catalog.md}, "模型、价格、周期规则等纯数据可以从带数字签名的目录热更新。协议适配 Java
 * 代码只能随正式版本升级，禁止下载并执行远程插件"}).
 *
 * <p>
 * Resource layout on the classpath:
 *
 * <pre>
 * catalog/provider-catalog.json    versioned manifest
 * catalog/provider-catalog.sig     Ed25519 signature over the exact manifest bytes
 * catalog/keys/catalog-public.pem  verification public key
 * </pre>
 */
public final class ProviderCatalog {

    /** Classpath base for the built-in catalog resources. */
    public static final String RESOURCE_BASE = "catalog/provider-catalog";

    private final List<ProviderProductDefinition> definitions;
    private final Map<String, ProviderProductDefinition> byId;

    private ProviderCatalog(List<ProviderProductDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
        Map<String, ProviderProductDefinition> map = new LinkedHashMap<>();
        for (ProviderProductDefinition d : definitions) {
            map.put(d.id(), d);
        }
        this.byId = Map.copyOf(map);
    }

    /**
     * Loads the built-in classpath catalog and verifies it with the bundled public
     * key.
     */
    public static ProviderCatalog loadBuiltIn() {
        return loadBuiltIn(CatalogKeyLoader.loadDefault());
    }

    /**
     * Loads the built-in classpath catalog and verifies it with an explicit public
     * key (used when a deployment overrides the default key).
     *
     * @throws CatalogLoadException
     *             on missing resources, signature failure or schema violations
     */
    public static ProviderCatalog loadBuiltIn(java.security.PublicKey publicKey) {
        ClassLoader loader = ProviderCatalog.class.getClassLoader();
        try (var json = loader.getResourceAsStream(RESOURCE_BASE + ".json");
                var sig = loader.getResourceAsStream(RESOURCE_BASE + ".sig")) {
            if (json == null || sig == null) {
                throw new CatalogLoadException("Catalog resources not found on classpath: " + RESOURCE_BASE);
            }
            byte[] data = json.readAllBytes();
            byte[] signature = sig.readAllBytes();
            return new ProviderCatalog(data, signature, publicKey);
        } catch (IOException e) {
            throw new CatalogLoadException("Failed to read catalog resources", e);
        }
    }

    /**
     * Constructs a catalog from raw manifest bytes. Package-visible entry used by
     * tests and by future admin-managed catalogs; {@link #loadBuiltIn} is the
     * production path.
     */
    public static ProviderCatalog fromBytes(byte[] manifest, byte[] signature, java.security.PublicKey publicKey) {
        return new ProviderCatalog(manifest, signature, publicKey);
    }

    private ProviderCatalog(byte[] manifest, byte[] signature, java.security.PublicKey publicKey) {
        try {
            CatalogSignatureVerifier.verify(manifest, signature, publicKey);
        } catch (CatalogSignatureException e) {
            throw new CatalogLoadException("Catalog signature verification failed: " + e.getMessage(), e);
        }
        try {
            this.definitions = List.copyOf(new CatalogManifestValidator().validate(manifest));
        } catch (CatalogManifestException e) {
            throw new CatalogLoadException(e.getMessage(), e);
        }
        Map<String, ProviderProductDefinition> map = new LinkedHashMap<>();
        for (ProviderProductDefinition d : definitions) {
            map.put(d.id(), d);
        }
        this.byId = Map.copyOf(map);
    }

    /** All product definitions in catalog order. */
    public List<ProviderProductDefinition> definitions() {
        return definitions;
    }

    /** Looks up a product by its stable id. */
    public Optional<ProviderProductDefinition> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }
}
