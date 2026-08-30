package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.miqrokey.adapters.catalog.ProviderCatalog;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.ProviderProductDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Seeds {@code providers} / {@code provider_products} from the signed
 * provider catalog at startup. The catalog is the only trusted source for
 * upstream URLs (CLAUDE.md: all upstream URLs come from compiled adapters or
 * the signed catalog), so products are never hand-entered — the tables are a
 * read-mostly mirror. Idempotent: existing product codes are left untouched.
 */
@Component
public class CatalogSeedService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogSeedService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ProviderCatalog catalog = ProviderCatalog.loadBuiltIn();
            List<ProviderProductDefinition> products = catalog.definitions();
            int providers = 0;
            int productsSeeded = 0;
            for (ProviderProductDefinition product : products) {
                UUID providerId = providerId(product.vendor());
                if (upsertProvider(providerId, product.vendor())) {
                    providers++;
                }
                if (upsertProduct(providerId, product)) {
                    productsSeeded++;
                }
            }
            log.info("Catalog seed: {} providers, {} products", providers, productsSeeded);
        } catch (Exception e) {
            // A broken/missing catalog must not silently pass: the catalog is
            // the trust root for upstream routing.
            throw new IllegalStateException("Failed to seed provider catalog", e);
        }
    }

    private boolean upsertProvider(UUID id, String slug) {
        return jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version, created_at, updated_at)
                VALUES (:id, :slug, :slug, 'ACTIVE', 0, now(), now())
                ON CONFLICT (slug) DO NOTHING
                """, new MapSqlParameterSource("id", id).addValue("slug", slug)) == 1;
    }

    private boolean upsertProduct(UUID providerId, ProviderProductDefinition product) {
        String code = product.adapterId();
        String protocols = product.protocols().stream().map(ProtocolFamily::name)
                .collect(Collectors.joining("\",\"", "[\"", "\"]"));
        String baseUrlTemplates = "[{\"url\":\"" + escapeJson(product.baseUrlTemplate().toString()) + "\"}]";
        return jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     quota_topology, supported_wire_protocols, base_url_templates, auth_scheme,
                     model_catalog_strategy, implementation_status, balance_authority, version,
                     created_at, updated_at)
                VALUES (:id, :providerId, :code, :displayName, 'PAYG', 'SINGLE_SHARED', 'NONE',
                        :protocols::jsonb, :baseUrlTemplates::jsonb, :authScheme::jsonb,
                        :modelCatalogStrategy, 'DOCUMENTED', 'UNAVAILABLE', 0, now(), now())
                ON CONFLICT (provider_id, product_code) DO NOTHING
                """, new MapSqlParameterSource("id", UUID.nameUUIDFromBytes(("product:" + code).getBytes(StandardCharsets.UTF_8)))
                .addValue("providerId", providerId)
                .addValue("code", code)
                .addValue("displayName", product.displayName())
                .addValue("protocols", protocols)
                .addValue("baseUrlTemplates", baseUrlTemplates)
                .addValue("authScheme", "{\"type\":\"bearer\"}")
                .addValue("modelCatalogStrategy", product.modelCatalogMode().name())) == 1;
    }

    private static UUID providerId(String slug) {
        return UUID.nameUUIDFromBytes(("provider:" + slug).getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
