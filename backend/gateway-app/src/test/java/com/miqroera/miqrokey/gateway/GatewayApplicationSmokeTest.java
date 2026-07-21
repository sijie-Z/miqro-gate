package com.miqroera.miqrokey.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal smoke test to verify the Gateway application context loads.
 *
 * <p>
 * The {@code control-plane-app} test dependency brings JDBC-related classes
 * onto the classpath (for ArchUnit cross-module checks). We exclude DataSource
 * auto-configuration here so the reactive Gateway context starts cleanly
 * without a database, while the ArchUnit tests in the same module still have
 * access to both module JARs.
 * </p>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"})
@DisplayName("Gateway application context")
class GatewayApplicationSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("should load application context successfully")
    void shouldLoadApplicationContext() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("gatewayApplication")).isTrue();
    }

    @Test
    @DisplayName("should use reactive web application type")
    void shouldUseReactiveWebApplicationType() {
        var env = context.getEnvironment();
        var webType = env.getProperty("spring.main.web-application-type");
        assertThat(webType).isEqualTo("reactive");
    }

    @Test
    @DisplayName("should use the documented gateway port by default")
    void shouldUseDocumentedGatewayPortByDefault() {
        assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(8081);
    }

    @Test
    @DisplayName("should not expose metrics on the public gateway port")
    void shouldNotExposeMetricsOnPublicGatewayPort() {
        var exposedEndpoints = context.getEnvironment().getProperty("management.endpoints.web.exposure.include");
        assertThat(exposedEndpoints).doesNotContain("metrics", "prometheus");
    }
}
