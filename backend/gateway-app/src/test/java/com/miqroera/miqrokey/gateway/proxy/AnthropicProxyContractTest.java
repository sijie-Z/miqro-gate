package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "spring.main.web-application-type=reactive", "logging.level.com.miqroera.miqrokey.gateway.proxy=DEBUG"})
@DisplayName("Anthropic transparent proxy contract")
class AnthropicProxyContractTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockProvider::getBaseUrl);
    }

    @BeforeAll
    static void startMockProvider() {
        // started in static initializer
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
    // Non-streaming response tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Non-streaming responses")
    class NonStreaming {

        @Test
        @DisplayName("should preserve request method, path and body")
        void shouldPreserveRequestMethodPathAndBody() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

            byte[] respBody = webTestClient.post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            assertThat(respBody).isNotNull();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            var req = captured.get(0);
            assertThat(req.method).isEqualTo("POST");
            assertThat(req.path).isEqualTo("/v1/messages");
            assertThat(req.bodyBytes)
                    .isEqualTo(AnthropicFixtures.REQUEST_NON_STREAMING.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should preserve response status, headers and raw JSON body")
        void shouldPreserveResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").header("x-request-id", "req_test_001")
                    .header("x-custom-header", "custom-value").body(AnthropicFixtures.RESPONSE_BASIC).build());

            byte[] responseBody = webTestClient.post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals("x-request-id", "req_test_001").expectHeader()
                    .valueEquals("x-custom-header", "custom-value").expectBody().returnResult().getResponseBody();

            assertThat(responseBody).isEqualTo(AnthropicFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should strip credential headers from upstream request")
        void shouldStripCredentialHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/messages").header("Authorization", "Bearer sk-ant-vk-test-12345")
                    .header("x-api-key", "sk-ant-vk-test-67890").header("api-key", "sk-ant-vk-test-99999")
                    .header("anthropic-version", AnthropicFixtures.ANTHROPIC_VERSION_VALUE)
                    .header("anthropic-beta", AnthropicFixtures.ANTHROPIC_BETA_VALUE)
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            var req = captured.get(0);
            assertThat(req.header("Authorization")).isNull();
            assertThat(req.header("x-api-key")).isNull();
            assertThat(req.header("api-key")).isNull();
            assertThat(req.header("anthropic-version")).isEqualTo(AnthropicFixtures.ANTHROPIC_VERSION_VALUE);
            assertThat(req.header("anthropic-beta")).isEqualTo(AnthropicFixtures.ANTHROPIC_BETA_VALUE);
        }

        @Test
        @DisplayName("should strip hop-by-hop headers from upstream request")
        void shouldStripHopByHopHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/messages").header("Connection", "keep-alive")
                    .header("Keep-Alive", "timeout=5")
                    .header("anthropic-version", AnthropicFixtures.ANTHROPIC_VERSION_VALUE)
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            var req = captured.get(0);
            // Hop-by-hop headers must be stripped
            assertThat(req.header("Connection")).isNull();
            assertThat(req.header("Keep-Alive")).isNull();
            // Application headers must be preserved
            assertThat(req.header("anthropic-version")).isEqualTo(AnthropicFixtures.ANTHROPIC_VERSION_VALUE);
        }

        @Test
        @DisplayName("should preserve the raw query octets and ordering")
        void shouldPreserveQueryParameters() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

            String rawTarget = "/v1/messages?path=a%2Fb&value=a+b&x=1&flag&x=2";
            HttpClient.create().post().uri("http://localhost:" + gatewayPort + rawTarget)
                    .send(ByteBufFlux.fromString(Mono.just(AnthropicFixtures.REQUEST_NON_STREAMING)))
                    .responseSingle((response, body) -> body.asByteArray()).block(Duration.ofSeconds(10));

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured.get(0).path).isEqualTo(rawTarget);
        }

        @Test
        @DisplayName("should return upstream error status and body")
        void shouldReturnUpstreamError() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(400)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_ERROR_400).build());

            byte[] responseBody = webTestClient.post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isBadRequest()
                    .expectBody().returnResult().getResponseBody();

            String body = new String(Objects.requireNonNull(responseBody), StandardCharsets.UTF_8);
            assertThat(body).contains("invalid_request_error");
        }

        @Test
        @DisplayName("should preserve a non-standard upstream status and exact error body")
        void shouldPreserveNonStandardStatus() {
            String upstreamBody = "{\"type\":\"error\",\"message\":\"synthetic overload\"}";
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(529)
                    .contentType("application/json").body(upstreamBody).build());

            byte[] responseBody = webTestClient.post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isEqualTo(529)
                    .expectBody().returnResult().getResponseBody();

            assertThat(responseBody).isEqualTo(upstreamBody.getBytes(StandardCharsets.UTF_8));
        }
    }

    // -------------------------------------------------------------------
    // Streaming (SSE) response tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Streaming SSE responses")
    class Streaming {

        @Test
        @DisplayName("should proxy SSE streaming response preserving event order")
        void shouldProxySseStream() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

            byte[] fullBody = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            assertThat(fullBody).isEqualTo(AnthropicFixtures.RESPONSE_STREAMING_SSE.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should proxy SSE with tool_use content blocks")
        void shouldProxySseWithToolUse() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(AnthropicFixtures.RESPONSE_STREAMING_TOOL_USE).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_WITH_TOOLS)
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("\"tool_use\"");
            assertThat(fullBody).contains("get_weather");
            assertThat(fullBody).contains("\"input_json_delta\"");
        }

        @Test
        @DisplayName("should proxy SSE with thinking content blocks")
        void shouldProxySseWithThinking() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(AnthropicFixtures.RESPONSE_STREAMING_THINKING).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("\"thinking\"");
            assertThat(fullBody).contains("\"thinking_delta\"");
            assertThat(fullBody).contains("Let me analyze");
        }

        @Test
        @DisplayName("should preserve cache usage fields in SSE")
        void shouldPreserveCacheUsageInSse() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(AnthropicFixtures.RESPONSE_STREAMING_CACHE_USAGE).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_WITH_CACHE)
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("\"cache_creation_input_tokens\":150");
            assertThat(fullBody).contains("\"cache_read_input_tokens\":300");
        }

        @Test
        @DisplayName("should not corrupt UTF-8 characters when split across network chunks")
        void shouldNotCorruptUtf8SplitChunks() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(AnthropicFixtures.RESPONSE_STREAMING_UTF8).streaming(true)
                    .utf8SplitChunks(true).chunkDelay(Duration.ofMillis(10)).build());

            byte[] rawBytes = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_WITH_UTF8)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            assertThat(rawBytes).isEqualTo(AnthropicFixtures.RESPONSE_STREAMING_UTF8.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should not aggregate slow streaming into a complete response")
        void shouldNotAggregateSlowStreaming() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                    .chunkDelay(Duration.ofMillis(50)).build());

            Flux<String> responseBody = webTestClient.post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_STREAMING).exchange().expectStatus().isOk()
                    .returnResult(String.class).getResponseBody();

            StepVerifier.create(responseBody).expectNextCount(1).expectNextCount(1).thenConsumeWhile(v -> true)
                    .verifyComplete();
        }
    }

    // -------------------------------------------------------------------
    // Client cancellation test
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Client cancellation")
    class Cancellation {

        @Test
        @DisplayName("should close the upstream connection after client cancellation")
        void shouldPropagateClientCancellation() throws Exception {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(AnthropicFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                    .chunkDelay(Duration.ofMillis(100)).build());
            Mono<Void> upstreamCancellation = mockProvider.cancellationSignal();

            // Use exchangeToFlux to stream the response through the Gateway.
            // When the StepVerifier cancels after one chunk, the Gateway's
            // writeWith detects the cancellation and propagates it to the
            // upstream WebClient, which closes the TCP connection to the
            // mock provider.
            Flux<org.springframework.core.io.buffer.DataBuffer> responseBody = WebClient
                    .create("http://localhost:" + gatewayPort).post().uri("/v1/messages")
                    .bodyValue(AnthropicFixtures.REQUEST_STREAMING).exchangeToFlux(
                            response -> response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class));

            StepVerifier.create(responseBody).consumeNextWith(DataBufferUtils::release).thenCancel()
                    .verify(Duration.ofSeconds(15));

            StepVerifier.create(upstreamCancellation).expectComplete().verify(Duration.ofSeconds(10));
            assertThat(mockProvider.wasUpstreamCancelled()).isTrue();
        }
    }

    // -------------------------------------------------------------------
    // Non-streaming special responses (tool_use, thinking, cache)
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Non-streaming special responses")
    class NonStreamingSpecial {

        @Test
        @DisplayName("should preserve a tool_result request byte-for-byte")
        void shouldProxyToolResultRequest() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_WITH_TOOL_RESULT).exchange()
                    .expectStatus().isOk().expectBody().returnResult().getResponseBody();

            assertThat(mockProvider.getCapturedRequests().get(0).bodyBytes)
                    .isEqualTo(AnthropicFixtures.REQUEST_WITH_TOOL_RESULT.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should proxy tool_use response correctly")
        void shouldProxyToolUseResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_TOOL_USE).build());

            byte[] body = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_WITH_TOOLS)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("\"type\":\"tool_use\"");
            assertThat(text).contains("get_weather");
            assertThat(text).contains("\"stop_reason\":\"tool_use\"");
        }

        @Test
        @DisplayName("should proxy thinking response correctly")
        void shouldProxyThinkingResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_THINKING).build());

            byte[] body = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("\"type\":\"thinking\"");
            assertThat(text).contains("I need to understand");
        }

        @Test
        @DisplayName("should preserve prompt cache usage fields")
        void shouldPreservePromptCacheUsage() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(AnthropicFixtures.RESPONSE_CACHE_USAGE).build());

            byte[] body = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_WITH_CACHE)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("\"cache_creation_input_tokens\":150");
            assertThat(text).contains("\"cache_read_input_tokens\":300");
        }
    }
}
