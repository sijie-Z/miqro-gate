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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kill switch for the #740 content-filter shadow: no
 * {@code miqrokey.gateway.content-filter.*} property is set on this class or
 * anywhere else in the test, so the {@code application.yml} default
 * ({@code enabled: false}) is what runs here. If that default ever flipped, the
 * evaluations counter would appear on the first request and these assertions
 * would fail; the byte assertions pin that off means off — the forwarded
 * request and the relayed reply are exactly what they were before #740.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive"})
@AutoConfigureWebTestClient(timeout = "30s")
@Import(GatewayAuthTestConfig.class)
@DisplayName("Content-filter shadow disabled by default (#740)")
class ContentFilterShadowDisabledTest {

    private static final String EVALUATIONS = "miqrokey_gateway_content_filter_evaluations_total";
    private static final String HITS = "miqrokey_gateway_content_filter_hits_total";
    private static final String MATCH_SECONDS = "miqrokey_gateway_content_filter_match_seconds";

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
    @DisplayName("non-streaming: no content-filter meter exists and the bytes are untouched")
    void nonStreamingTrafficIsUntouched() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

        // The same byte-level assertion the contract tests make: the reply is
        // the provider's reply, byte for byte.
        assertThat(response).isEqualTo(AnthropicFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        var captured = mockProvider.getCapturedRequests();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).bodyBytes)
                .isEqualTo(AnthropicFixtures.REQUEST_NON_STREAMING.getBytes(StandardCharsets.UTF_8));
        // Nothing was evaluated: the disabled shadow never touches the registry.
        assertThat(meterRegistry.find(EVALUATIONS).counter()).isNull();
        assertThat(meterRegistry.find(HITS).counter()).isNull();
        assertThat(meterRegistry.find(MATCH_SECONDS).timer()).isNull();
    }

    @Test
    @DisplayName("streaming: the SSE body is byte-identical and no meter appears")
    void streamingTrafficIsUntouched() {
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                        .body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

        assertThat(response).isEqualTo(AnthropicFixtures.RESPONSE_STREAMING_SSE.getBytes(StandardCharsets.UTF_8));
        assertThat(meterRegistry.find(EVALUATIONS).counter()).isNull();
    }
}
