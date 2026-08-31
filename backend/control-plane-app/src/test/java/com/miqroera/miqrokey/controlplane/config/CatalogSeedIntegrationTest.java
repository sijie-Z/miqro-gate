package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
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
}
