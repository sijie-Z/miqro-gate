package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.deepseek.DeepSeekPaygAdapter;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.client.HttpProviderClient;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ModelDefinition;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import com.sun.net.httpserver.HttpServer;
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final String PRODUCT_CODE = "catalog-test-product";

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

    @Test
    @DisplayName("end-to-end: real adapter + real ProviderClient against the official API shape (G3.1)")
    void refreshProductEndToEndWithOfficialApiShape() throws Exception {
        // The real DeepSeek adapter resolves product code 'deepseek-payg-api';
        // the startup catalog seed owns that code, so drop the seed row and
        // re-seed the fixture row with the same code under the fixture product.
        jdbc.update("DELETE FROM provider_products WHERE product_code = 'deepseek-payg-api'",
                new MapSqlParameterSource());
        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:providerId, 'catalog-test-provider', 'Catalog Test Provider', 'ACTIVE', 0)
                """, new MapSqlParameterSource("providerId", PROVIDER_ID));
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:productId, :providerId, 'deepseek-payg-api', 'Catalog Test Product', 'PAYG',
                        'SINGLE_SHARED', '["messages"]', '[{"url":"https://api.test.example"}]',
                        '{"type":"bearer"}', 'VERIFIED', 0)
                """, new MapSqlParameterSource("productId", PRODUCT_ID).addValue("providerId", PROVIDER_ID));
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = """
                    {"object":"list","data":[
                      {"id":"deepseek-chat","object":"model","display_name":"DeepSeek-V3.2"},
                      {"id":"deepseek-reasoner","object":"model","owned_by":"deepseek"}
                    ]}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekPaygAdapter adapter = new DeepSeekPaygAdapter(new ObjectMapper());
            HttpProviderClient client = new HttpProviderClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "Authorization", "Bearer sk-test",
                    new UpstreamTargetValidator(List.of("127.0.0.0/8")), Duration.ofSeconds(2), Duration.ofSeconds(5),
                    1024 * 1024);

            modelCatalogService.refreshProduct(adapter, client);

            assertThat(catalogModels()).containsExactlyInAnyOrder("deepseek-chat", "deepseek-reasoner");
            assertThat(hits).hasValue(1);
            assertThat(authorization).hasValue("Bearer sk-test");
        } finally {
            server.stop(0);
        }
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
