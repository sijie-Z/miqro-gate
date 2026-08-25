package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ModelDefinition;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end {@code model_catalog} maintenance test against real PostgreSQL: a
 * successful snapshot replaces the product's rows (stale models disappear), a
 * failed fetch leaves the last successful catalog untouched, and an unknown
 * product code writes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("ModelCatalogService (PostgreSQL)")
class ModelCatalogServiceIntegrationTest extends AbstractControlPlaneIntegrationTest {

    private static final UUID PROVIDER_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_ID = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
    private static final String PRODUCT_CODE = "deepseek-payg-api";

    @DynamicPropertySource
    public static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    ModelCatalogService modelCatalogService;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM model_catalog WHERE provider_product_id = :productId",
                new MapSqlParameterSource("productId", PRODUCT_ID));
        jdbc.update("DELETE FROM provider_products WHERE id = :productId",
                new MapSqlParameterSource("productId", PRODUCT_ID));
        jdbc.update("DELETE FROM providers WHERE id = :providerId",
                new MapSqlParameterSource("providerId", PROVIDER_ID));
    }

    @Test
    @DisplayName("a successful snapshot replaces the product's rows — stale models disappear")
    void applySnapshotReplacesRows() {
        seedProduct();
        modelCatalogService.applySnapshot(snapshot("m1", "m2"));

        assertThat(catalogModels()).containsExactlyInAnyOrder("m1", "m2");

        // Second successful fetch narrows the catalog: m2 must vanish.
        modelCatalogService.applySnapshot(snapshot("m1"));

        assertThat(catalogModels()).containsExactly("m1");
    }

    @Test
    @DisplayName("an unknown product code writes nothing and does not throw")
    void applySnapshotUnknownProduct() {
        modelCatalogService.applySnapshot(
                new ModelCatalogSnapshot("ghost-product", List.of(new ModelDefinition("m1")), Instant.EPOCH));

        assertThat(catalogModels()).isEmpty();
    }

    @Test
    @DisplayName("a failed fetch keeps the last successful catalog")
    void refreshProductFailureKeepsLastGood() {
        seedProduct();
        modelCatalogService.applySnapshot(snapshot("m1", "m2"));

        ProviderProductAdapter adapter = mock(ProviderProductAdapter.class);
        ProviderClient client = mock(ProviderClient.class);
        when(adapter.fetchModels(client)).thenReturn(Mono.error(new RuntimeException("provider unreachable")));

        modelCatalogService.refreshProduct(adapter, client);

        assertThat(catalogModels()).containsExactlyInAnyOrder("m1", "m2");
    }

    @Test
    @DisplayName("a successful fetch via the adapter replaces the catalog")
    void refreshProductSuccessApplies() {
        seedProduct();
        ProviderProductAdapter adapter = mock(ProviderProductAdapter.class);
        ProviderClient client = mock(ProviderClient.class);
        when(adapter.fetchModels(client)).thenReturn(Mono.just(new ModelCatalogSnapshot(PRODUCT_CODE,
                List.of(new ModelDefinition("a1"), new ModelDefinition("a2")), Instant.EPOCH)));

        modelCatalogService.refreshProduct(adapter, client);

        assertThat(catalogModels()).containsExactlyInAnyOrder("a1", "a2");
    }

    private void seedProduct() {
        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:providerId, 'catalog-test-provider', 'Catalog Test Provider', 'ACTIVE', 0)
                """, new MapSqlParameterSource("providerId", PROVIDER_ID));
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:productId, :providerId, :productCode, 'Catalog Test Product', 'PAYG', 'SINGLE_SHARED',
                        '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                """, new MapSqlParameterSource("productId", PRODUCT_ID).addValue("providerId", PROVIDER_ID)
                .addValue("productCode", PRODUCT_CODE));
    }

    private Set<String> catalogModels() {
        List<String> models = jdbc.query("""
                SELECT model_id FROM model_catalog WHERE provider_product_id = :productId ORDER BY model_id
                """, new MapSqlParameterSource("productId", PRODUCT_ID), (rs, i) -> rs.getString(1));
        return models.stream().collect(Collectors.toUnmodifiableSet());
    }

    private static ModelCatalogSnapshot snapshot(String... modelIds) {
        List<ModelDefinition> models = java.util.Arrays.stream(modelIds).map(m -> new ModelDefinition(m, "Model " + m))
                .toList();
        return new ModelCatalogSnapshot(PRODUCT_CODE, models, Instant.EPOCH);
    }

    static class BootstrapHelper {
        private static final Path SECRET_FILE = Path.of(System.getProperty("java.io.tmpdir"),
                "miqrokey-bootstrap-test");
        private static final String SECRET = "bootstrap-secret-for-g23-tests";

        static {
            try {
                Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static Path secretFile() {
            return SECRET_FILE;
        }
    }
}
