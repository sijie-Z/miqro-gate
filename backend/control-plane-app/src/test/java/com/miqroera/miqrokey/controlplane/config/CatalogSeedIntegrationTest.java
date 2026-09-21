package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.spi.AdapterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signed provider catalog seeds providers / provider_products at startup,
 * so the admin product dropdown is never empty on a fresh database.
 */
@SpringBootTest
@Tag("integration")
@DisplayName("Catalog seed on startup")
class CatalogSeedIntegrationTest {
    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
    }

    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    CatalogSeedService seedService;
    @Autowired
    AdapterRegistry adapterRegistry;

    @BeforeEach
    void seed() {
        // The runner fires on startup, but shared-container tests may have
        // wiped the tables; seeding is idempotent, so just run it.
        seedService.run(null);
    }

    @Test
    @DisplayName("startup seeds all 23 catalog products with trusted URLs")
    void seedsCatalog() {
        Integer products = jdbc.queryForObject("SELECT count(*) FROM provider_products",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), Integer.class);
        Integer providers = jdbc.queryForObject("SELECT count(*) FROM providers",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), Integer.class);

        assertThat(products).isGreaterThanOrEqualTo(23);
        assertThat(providers).isGreaterThanOrEqualTo(8);

        // Spot-check DeepSeek: product code + https base URL from the signed
        // catalog only.
        String baseUrl = jdbc.queryForObject(
                "SELECT base_url_templates::text FROM provider_products WHERE product_code = 'deepseek-payg-api'",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), String.class);
        assertThat(baseUrl).contains("https://api.deepseek.com");
    }

    /**
     * #735: the seeded {@code implementation_status} is <em>derived from the
     * registry</em>, not declared — every product that has a registered adapter
     * (one per P0 product, see {@code AdapterRegistryFactory}) must be seeded as
     * {@code IMPLEMENTED}. Before this change the seed hardcoded {@code DOCUMENTED}
     * for all 23, so the console showed the whole catalog as "not implemented"
     * while every one of them had an adapter with passing fixture/mock contract
     * tests (the contract's own definition of {@code IMPLEMENTED}, §7).
     *
     * <p>
     * Scoped to the registry's product codes on purpose: sibling tests in the
     * shared container insert their own {@code provider_products} rows.
     * </p>
     */
    @Test
    @DisplayName("#735: implementation_status follows the adapter registry, never a literal")
    void implementationStatusFollowsTheRegistry() {
        assertThat(adapterRegistry.adapterIds()).as("one adapter per P0 catalog product")
                .hasSizeGreaterThanOrEqualTo(23);
        for (String productCode : adapterRegistry.adapterIds()) {
            String status = jdbc.queryForObject(
                    "SELECT implementation_status FROM provider_products WHERE product_code = :code",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("code", productCode),
                    String.class);
            assertThat(status).as("implementation_status of %s", productCode).isEqualTo("IMPLEMENTED");
        }
    }
}
