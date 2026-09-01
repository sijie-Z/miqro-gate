package com.miqroera.miqrokey.controlplane;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared singleton PostgreSQL container for all control-plane integration
 * tests. Avoids the overhead and potential CI hangs caused by each test class
 * starting its own container.
 *
 * <p>
 * Subclasses get a fully configured DataSource pointing at the shared
 * container. Each test is responsible for cleaning its own data in
 * {@code @BeforeEach} / {@code @AfterEach}.
 * </p>
 */
public abstract class AbstractControlPlaneIntegrationTest {

    public static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName
                .parse("postgres:17.6-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName("miqrokey_test").withUsername("miqrokey_test")
                .withPassword("miqrokey_test").withCommand("postgres", "-c", "max_connections=200");
        POSTGRES.start();
    }

    @DynamicPropertySource
    public static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.clean-disabled", () -> "true");
        // The shared container allows 100 connections; cached test contexts each
        // hold a pool, so the production default of 20 per context lets 5+ cached
        // contexts exhaust the server and raw probe connections fail with
        // "too many clients" (RouteSnapshotRefreshNotifierTest). Test workloads
        // run fine on a smaller pool, leaving headroom.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
    }
}
