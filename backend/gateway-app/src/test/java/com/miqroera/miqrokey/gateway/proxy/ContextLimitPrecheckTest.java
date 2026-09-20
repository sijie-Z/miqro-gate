package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Contract coverage for the #553 context-limit pre-check, with the guard
 * enabled and a deliberately small threshold so ordinary fixtures are enough to
 * cross it.
 *
 * <p>
 * The load-bearing assertion in every rejection test is
 * {@code mockProvider.getCapturedRequests().isEmpty()}: an oversized context is
 * refused at the gateway, so the provider is never contacted and the caller
 * never pays for a request that could not succeed.
 * </p>
 *
 * <p>
 * Precedence is part of the contract: authentication and model authorization
 * are evaluated first, so an oversized body can never turn an auth error into a
 * size oracle.
 * </p>
 *
 * <p>
 * #1032: the client's response budget is stated explicitly. Spring Boot's
 * default is 5 seconds and this module never configured it, so under CI CPU
 * contention a 2KB body through an in-process mock provider occasionally missed
 * the budget and surfaced as
 * {@code Timeout on blocking read for 5000000000 NANOSECONDS} on a run that was
 * otherwise correct. These tests assert behaviour, never latency — a genuine
 * hang still fails here, just later. (The budget comes from this annotation,
 * not from {@code spring.test.webtestclient.timeout}: the property is ignored
 * unless the annotation is present, which is why the module-wide default was
 * invisible.)
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.context-limit.enabled=true",
        "miqrokey.gateway.context-limit.threshold-chars=2000"})
@AutoConfigureWebTestClient(timeout = "30s")
@Import(GatewayAuthTestConfig.class)
@DisplayName("Gateway context-limit pre-check (#553)")
class ContextLimitPrecheckTest {

    private static final int THRESHOLD = 2000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    // -------------------------------------------------------------------
    // Rejection: 413, protocol-compatible envelope, zero upstream traffic
    // -------------------------------------------------------------------

    @Test
    @DisplayName("rejects an oversized Anthropic request with 413 and never contacts the upstream")
    void rejectsOversizedAnthropicRequest() throws Exception {
        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(anthropicBodyOfLength(THRESHOLD + 1))
                .exchange().expectStatus().isEqualTo(413).expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody().returnResult().getResponseBody();

        assertThat(errorType(response)).isEqualTo("context_limit_exceeded");
        // Anthropic envelope: top-level "type":"error" discriminator, exactly as
        // on the gateway's other /v1/messages error paths.
        assertThat(OBJECT_MAPPER.readTree(response).path("type").asText()).isEqualTo("error");
        assertThat(mockProvider.getCapturedRequests()).isEmpty();
    }

    @Test
    @DisplayName("rejects an oversized Chat Completions request with an OpenAI-shaped 413")
    void rejectsOversizedChatRequest() throws Exception {
        byte[] response = webTestClient.post().uri("/v1/chat/completions").bodyValue(chatBodyOfLength(THRESHOLD + 1))
                .exchange().expectStatus().isEqualTo(413).expectBody().returnResult().getResponseBody();

        assertThat(errorType(response)).isEqualTo("context_limit_exceeded");
        // OpenAI envelope: no top-level "type":"error" discriminator.
        assertThat(OBJECT_MAPPER.readTree(response).has("type")).isFalse();
        assertThat(mockProvider.getCapturedRequests()).isEmpty();
    }

    @Test
    @DisplayName("rejects an oversized Responses request with an OpenAI-shaped 413")
    void rejectsOversizedResponsesRequest() throws Exception {
        byte[] response = webTestClient.post().uri("/v1/responses").bodyValue(responsesBodyOfLength(THRESHOLD + 1))
                .exchange().expectStatus().isEqualTo(413).expectBody().returnResult().getResponseBody();

        assertThat(errorType(response)).isEqualTo("context_limit_exceeded");
        assertThat(mockProvider.getCapturedRequests()).isEmpty();
    }

    @Test
    @DisplayName("the error body reports the sizes but never the request content")
    void rejectionLeaksNoContent() {
        String filler = "SENSITIVE-PROMPT-MARKER";
        String body = anthropicBodyPrefix() + filler + "x".repeat(THRESHOLD) + anthropicBodySuffix();

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(body).exchange().expectStatus()
                .isEqualTo(413).expectBody().returnResult().getResponseBody();

        assertThat(new String(response, StandardCharsets.UTF_8)).doesNotContain(filler).doesNotContain("SENSITIVE");
    }

    // -------------------------------------------------------------------
    // Boundary and pass-through
    // -------------------------------------------------------------------

    @Test
    @DisplayName("accepts a request exactly at the limit and forwards it byte-identically")
    void acceptsBodyAtTheLimit() throws Exception {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
        String body = anthropicBodyOfLength(THRESHOLD);

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(body).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        // A request at the limit is a pass-through: the downstream payload is the
        // provider's, not the Anthropic-shaped 413 error envelope.
        assertThat(OBJECT_MAPPER.readTree(response).path("type").asText()).isNotEqualTo("error");
        var captured = mockProvider.getCapturedRequests();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).bodyBytes).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("leaves a normal request untouched: same bytes upstream, same response downstream")
    void normalRequestIsUnaffected() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

        assertThat(response).isEqualTo(AnthropicFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        var captured = mockProvider.getCapturedRequests();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).path).isEqualTo("/v1/messages");
        assertThat(captured.get(0).bodyBytes)
                .isEqualTo(AnthropicFixtures.REQUEST_NON_STREAMING.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("measures characters, not bytes: a multi-byte body under the limit is forwarded")
    void multiByteBodyIsMeasuredInCharacters() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
        // 1500 code points but 4500 bytes: a byte-based guard would reject this.
        String body = anthropicBodyPrefix() + "你".repeat(1500) + anthropicBodySuffix();
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(THRESHOLD);

        webTestClient.post().uri("/v1/messages").bodyValue(body).exchange().expectStatus().isOk();

        var captured = mockProvider.getCapturedRequests();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).bodyBytes).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------
    // Precedence — auth and model authorization come first
    // -------------------------------------------------------------------

    @Test
    @DisplayName("an oversized body does not bypass authentication")
    void oversizedBodyDoesNotBypassAuth() {
        webTestClient.post().uri("/v1/messages").header("Authorization", "Bearer not-a-key-at-all")
                .bodyValue(anthropicBodyOfLength(THRESHOLD * 2)).exchange().expectStatus().isNotFound();

        assertThat(mockProvider.getCapturedRequests()).isEmpty();
    }

    @Test
    @DisplayName("an oversized body does not bypass the model authorization check")
    void oversizedBodyDoesNotBypassModelCheck() throws Exception {
        byte[] response = webTestClient.post().uri("/v1/chat/completions")
                .bodyValue("{\"model\":\"denied-model\",\"messages\":[{\"role\":\"user\",\"content\":\""
                        + "x".repeat(THRESHOLD * 2) + "\"}]}")
                .exchange().expectStatus().isForbidden().expectBody().returnResult().getResponseBody();

        assertThat(errorType(response)).isEqualTo("model_not_allowed");
        assertThat(mockProvider.getCapturedRequests()).isEmpty();
    }

    // -------------------------------------------------------------------
    // Observability
    // -------------------------------------------------------------------

    @Test
    @DisplayName("counts the rejection on a zero-label metric")
    void countsRejections() {
        double before = meterRegistry.get("miqrokey_gateway_context_limit_rejected_total").counter().count();

        webTestClient.post().uri("/v1/messages").bodyValue(anthropicBodyOfLength(THRESHOLD + 1)).exchange()
                .expectStatus().isEqualTo(413);

        var counter = meterRegistry.get("miqrokey_gateway_context_limit_rejected_total").counter();
        assertThat(counter.count()).isEqualTo(before + 1);
        assertThat(counter.getId().getTags()).isEmpty();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static String anthropicBodyPrefix() {
        return "{\"model\":\"claude-sonnet-5-20250915\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\","
                + "\"content\":\"";
    }

    private static String anthropicBodySuffix() {
        return "\"}]}";
    }

    /**
     * An ASCII-only Anthropic body whose serialized length is exactly
     * {@code chars}.
     */
    private static String anthropicBodyOfLength(int chars) {
        int filler = chars - anthropicBodyPrefix().length() - anthropicBodySuffix().length();
        assertThat(filler).isPositive();
        return anthropicBodyPrefix() + "x".repeat(filler) + anthropicBodySuffix();
    }

    /**
     * An ASCII-only Chat Completions body whose serialized length is exactly
     * {@code chars}.
     */
    private static String chatBodyOfLength(int chars) {
        String prefix = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"";
        String suffix = "\"}]}";
        return prefix + "x".repeat(chars - prefix.length() - suffix.length()) + suffix;
    }

    /**
     * An ASCII-only Responses body whose serialized length is exactly
     * {@code chars}.
     */
    private static String responsesBodyOfLength(int chars) {
        String prefix = "{\"model\":\"gpt-4o-mini\",\"input\":\"";
        String suffix = "\"}";
        return prefix + "x".repeat(chars - prefix.length() - suffix.length()) + suffix;
    }

    private static String errorType(byte[] body) throws Exception {
        assertThat(body).isNotNull();
        return OBJECT_MAPPER.readTree(body).path("error").path("type").asText();
    }
}
