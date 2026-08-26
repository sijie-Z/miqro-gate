package com.miqroera.miqrokey.controlplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Optional monitoring profile (G6.1): Prometheus scrape endpoint and the
 * control-plane provider-call counters, verified over the real random port
 * exactly like a scrape would (MockMvc does not mount management endpoints).
 * The default profile keeps the endpoint closed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("monitoring")
@Tag("integration")
@DisplayName("Control plane monitoring profile (Prometheus)")
class ControlPlaneMonitoringProfileTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
    }

    @LocalServerPort
    int serverPort;

    @Autowired
    org.springframework.core.env.Environment environment;
    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("prometheus endpoint is served with the provider-call counter")
    void prometheusEndpointServesMetrics() throws Exception {
        // The monitoring profile must actually be loaded.
        org.assertj.core.api.Assertions.assertThat(environment.getProperty("management.metrics.tags.application"))
                .isEqualTo("miqrokey-control-plane");
        HttpResponse<String> scrape = HttpClient.newHttpClient().send(HttpRequest
                .newBuilder(URI.create("http://localhost:" + serverPort + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(scrape.statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(scrape.body()).contains("miqrokey_control_provider_calls_total");
    }
}
