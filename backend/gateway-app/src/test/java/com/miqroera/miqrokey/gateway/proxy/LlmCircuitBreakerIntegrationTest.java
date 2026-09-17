package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract coverage for the LLM-side circuit breaker (#741) with the feature
 * enabled and deliberately small thresholds.
 *
 * <p>
 * The load-bearing assertion in the fast-fail test is that the upstream is
 * never contacted while the breaker is OPEN — the failure loop must not keep
 * feeding the broken dependency. The probe test pins the recovery path: after
 * the open window one probe is released and its success closes the breaker.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.circuit-breaker.enabled=true",
        "miqrokey.gateway.circuit-breaker.min-requests=2", "miqrokey.gateway.circuit-breaker.open-seconds=1",
        "miqrokey.gateway.circuit-breaker.probe-count=1", "miqrokey.gateway.circuit-breaker.probe-success=1"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("LLM circuit breaker enabled (#741)")
class LlmCircuitBreakerIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LlmCircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockProvider::getBaseUrl);
    }

    @AfterAll
    static void stopMockProvider() {
        mockProvider.close();
    }

    @AfterEach
    void resetMockProvider() {
        mockProvider.reset();
    }

    @BeforeEach
    void resetBreaker() {
        // The registry is a context singleton: without this, an OPEN bucket
        // left by one test would fast-fail the next test's first call.
        registry.reset();
    }

    private void upstreamFailsWith(int status) {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(status)
                .contentType("application/json").body("{\"error\":{\"type\":\"upstream_broken\"}}").build());
    }

    private void upstreamSucceeds() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
    }

    private WebTestClient.ResponseSpec call() {
        return webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange();
    }

    @Test
    @DisplayName("after the configured failures the breaker fails fast with 503 circuit_open and no upstream call")
    void tripsAndFailsFast() throws Exception {
        upstreamFailsWith(500);

        // Control: the first two failures are relayed verbatim.
        call().expectStatus().isEqualTo(500);
        call().expectStatus().isEqualTo(500);
        assertThat(mockProvider.getCapturedRequests()).hasSize(2);

        // Third call: rejected at the gateway, protocol-shaped envelope.
        byte[] response = call().expectStatus().isEqualTo(503).expectBody().returnResult().getResponseBody();
        assertThat(OBJECT_MAPPER.readTree(response).path("error").path("type").asText()).isEqualTo("circuit_open");
        assertThat(OBJECT_MAPPER.readTree(response).path("type").asText()).isEqualTo("error");
        // The broken upstream is not contacted again.
        assertThat(mockProvider.getCapturedRequests()).hasSize(2);

        // A rejected call writes no lifecycle/usage row: verify by asking the
        // upstream again after recovery and counting only real calls below.
    }

    @Test
    @DisplayName("the half-open probe is released after the open window and its success closes the breaker")
    void probeRecoversAndCloses() throws Exception {
        upstreamFailsWith(500);
        call().expectStatus().isEqualTo(500);
        call().expectStatus().isEqualTo(500);
        call().expectStatus().isEqualTo(503); // open
        assertThat(mockProvider.getCapturedRequests()).hasSize(2);

        // Upstream recovers; wait past open-seconds=1.
        upstreamSucceeds();
        Thread.sleep(1_200);

        // The probe is released and succeeds -> breaker closes.
        call().expectStatus().isOk();
        assertThat(mockProvider.getCapturedRequests()).hasSize(3);

        // Closed again: normal traffic flows.
        call().expectStatus().isOk();
        assertThat(mockProvider.getCapturedRequests()).hasSize(4);
    }

    @Test
    @DisplayName("non-error statuses (404) feed the breaker as successes, never opening it")
    void nonErrorStatusDoesNotTrip() {
        upstreamFailsWith(404);

        for (int i = 0; i < 4; i++) {
            call().expectStatus().isEqualTo(404);
        }
        assertThat(mockProvider.getCapturedRequests()).hasSize(4);
    }
}
