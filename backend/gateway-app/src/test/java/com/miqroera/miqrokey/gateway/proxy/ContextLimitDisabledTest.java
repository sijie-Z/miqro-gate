package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kill switch for the #553 context-limit pre-check.
 *
 * <p>
 * The threshold is set far below the size of the fixtures on purpose: if
 * {@code enabled=false} were ignored, every request below would be rejected with
 * 413 and these tests would fail. Passing therefore proves the switch — not the
 * threshold — decided the outcome, and that turning the guard off restores
 * byte-for-byte today's behaviour.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.context-limit.enabled=false",
        "miqrokey.gateway.context-limit.threshold-chars=10"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("Gateway context-limit pre-check disabled (#553)")
class ContextLimitDisabledTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry meterRegistry;

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
    @DisplayName("forwards a body far above the configured threshold, byte-identically")
    void disabledGuardForwardsEverything() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
        String body = AnthropicFixtures.REQUEST_NON_STREAMING;
        assertThat(body.length()).isGreaterThan(10);

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(body).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(response).isEqualTo(AnthropicFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        var captured = mockProvider.getCapturedRequests();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).bodyBytes).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("keeps rejecting unauthenticated requests exactly as before")
    void disabledGuardLeavesAuthIntact() {
        webTestClient.post().uri("/v1/messages").header("Authorization", "Bearer not-a-key-at-all")
                .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isNotFound();

        assertThat(mockProvider.getCapturedRequests()).isEmpty();
    }

    @Test
    @DisplayName("never increments the rejection counter")
    void disabledGuardNeverCounts() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
        double before = meterRegistry.get("miqrokey_gateway_context_limit_rejected_total").counter().count();

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk();

        assertThat(meterRegistry.get("miqrokey_gateway_context_limit_rejected_total").counter().count())
                .isEqualTo(before);
    }
}
