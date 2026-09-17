package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * The kill switch for the #741 LLM circuit breaker.
 *
 * <p>
 * Thresholds are set to their most aggressive values on purpose: with
 * {@code enabled=false} ignored, every request below would trip immediately and
 * the assertions would fail. Passing therefore proves the switch — not the
 * thresholds — decided the outcome, and that turning the breaker off restores
 * byte-for-byte today's behavior.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.circuit-breaker.enabled=false",
        "miqrokey.gateway.circuit-breaker.min-requests=1", "miqrokey.gateway.circuit-breaker.error-ratio=1",
        "miqrokey.gateway.circuit-breaker.open-seconds=1"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("LLM circuit breaker disabled (#741)")
class LlmCircuitBreakerDisabledTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @Autowired
    private WebTestClient webTestClient;

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

    @Test
    @DisplayName("failures keep reaching the upstream verbatim; circuit_open never appears")
    void disabledKeepsFeedingTheUpstream() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(500)
                .contentType("application/json").body("{\"error\":{\"type\":\"upstream_broken\"}}").build());

        for (int i = 0; i < 5; i++) {
            byte[] response = webTestClient.post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isEqualTo(500)
                    .expectBody().returnResult().getResponseBody();
            assertThat(new String(response, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("circuit_open");
        }
        assertThat(mockProvider.getCapturedRequests()).hasSize(5);
    }

    @Test
    @DisplayName("successful traffic is untouched")
    void disabledLeavesSuccessAlone() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk();
    }
}
