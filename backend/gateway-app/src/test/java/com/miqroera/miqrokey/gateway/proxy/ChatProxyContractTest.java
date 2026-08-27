package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.ChatFixtures;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "logging.level.com.miqroera.miqrokey.gateway.proxy=DEBUG"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("OpenAI Chat Completions transparent proxy contract")
class ChatProxyContractTest {

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

    @Nested
    @DisplayName("Non-streaming responses")
    class NonStreaming {

        @Test
        @DisplayName("should preserve request method, path and exact body bytes")
        void shouldPreserveRequestMethodPathAndBody() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            byte[] respBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            assertThat(respBody).isNotNull();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            var req = captured.get(0);
            assertThat(req.method).isEqualTo("POST");
            assertThat(req.path).isEqualTo("/v1/chat/completions");
            assertThat(req.bodyBytes).isEqualTo(ChatFixtures.REQUEST_NON_STREAMING.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should preserve response status, headers and raw JSON body")
        void shouldPreserveResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").header("x-request-id", "chat_req_001")
                    .header("x-custom", "chat-test").body(ChatFixtures.RESPONSE_BASIC).build());

            byte[] responseBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals("x-request-id", "chat_req_001").expectHeader().valueEquals("x-custom", "chat-test")
                    .expectBody().returnResult().getResponseBody();

            assertThat(responseBody).isEqualTo(ChatFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should consume the presented key for auth and inject the real credential upstream")
        void shouldStripCredentialHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isOk().expectBody().returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            var req = captured.get(0);
            // The presented virtual key never reaches the upstream; the real
            // credential is injected into the same Authorization header instead.
            assertThat(req.header("Authorization")).isEqualTo(GatewayAuthTestConfig.UPSTREAM_CREDENTIAL_VALUE);
            assertThat(req.header("Authorization")).isNotEqualTo("Bearer " + GatewayTestKeys.DEFAULT_KEY.presented());
            assertThat(req.header("api-key")).isNull();
            // The gateway injects the real upstream credential instead.
            assertThat(req.header(GatewayAuthTestConfig.UPSTREAM_CREDENTIAL_HEADER))
                    .isEqualTo(GatewayAuthTestConfig.UPSTREAM_CREDENTIAL_VALUE);
        }

        @Test
        @DisplayName("should preserve the raw query octets and ordering")
        void shouldPreserveQueryParameters() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            String rawTarget = "/v1/chat/completions?path=a%2Fb&value=a+b&x=1&x=2";
            HttpClient.create()
                    .headers(h -> h.set("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())).post()
                    .uri("http://localhost:" + gatewayPort + rawTarget)
                    .send(ByteBufFlux.fromString(Mono.just(ChatFixtures.REQUEST_NON_STREAMING)))
                    .responseSingle((response, body) -> body.asByteArray()).block(Duration.ofSeconds(10));

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured.get(0).path).isEqualTo(rawTarget);
        }

        @Test
        @DisplayName("should return upstream error status and body")
        void shouldReturnUpstreamError() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(400)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_ERROR_400).build());

            byte[] responseBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isBadRequest().expectBody()
                    .returnResult().getResponseBody();

            String body = new String(Objects.requireNonNull(responseBody), StandardCharsets.UTF_8);
            assertThat(body).contains("invalid_request_error");
        }

        @Test
        @DisplayName("should preserve non-standard upstream status 529")
        void shouldPreserveNonStandardStatus() {
            String upstreamBody = "{\"error\":{\"message\":\"Overloaded\",\"type\":\"server_error\"}}";
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(529)
                    .contentType("application/json").body(upstreamBody).build());

            byte[] responseBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isEqualTo(529).expectBody()
                    .returnResult().getResponseBody();

            assertThat(responseBody).isEqualTo(upstreamBody.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should preserve unknown fields in request and response")
        void shouldPreserveUnknownFields() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_WITH_UNKNOWN_FIELDS).build());

            byte[] responseBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_WITH_UNKNOWN_FIELDS).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            assertThat(responseBody)
                    .isEqualTo(ChatFixtures.RESPONSE_WITH_UNKNOWN_FIELDS.getBytes(StandardCharsets.UTF_8));

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured.get(0).bodyBytes)
                    .isEqualTo(ChatFixtures.REQUEST_WITH_UNKNOWN_FIELDS.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("Streaming SSE responses")
    class Streaming {

        @Test
        @DisplayName("should proxy SSE streaming response preserving [DONE] terminator")
        void shouldProxySseStream() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ChatFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

            byte[] fullBody = webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            assertThat(fullBody).isEqualTo(ChatFixtures.RESPONSE_STREAMING_SSE.getBytes(StandardCharsets.UTF_8));
            String bodyStr = new String(fullBody, StandardCharsets.UTF_8);
            assertThat(bodyStr).contains("[DONE]");
        }

        @Test
        @DisplayName("should proxy SSE with tool call deltas")
        void shouldProxySseWithToolCallDeltas() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ChatFixtures.RESPONSE_STREAMING_TOOL_CALL).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_WITH_TOOLS).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("tool_calls");
            assertThat(fullBody).contains("get_weather");
            assertThat(fullBody).contains("\"finish_reason\":\"tool_calls\"");
        }

        @Test
        @DisplayName("should proxy SSE with reasoning_content deltas")
        void shouldProxySseWithReasoningContent() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ChatFixtures.RESPONSE_STREAMING_REASONING).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("reasoning_content");
            assertThat(fullBody).contains("Let me calculate");
        }

        @Test
        @DisplayName("should preserve usage fields in SSE")
        void shouldPreserveUsageInSse() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ChatFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_STREAMING)
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("\"prompt_tokens\":10");
            assertThat(fullBody).contains("\"completion_tokens\":7");
            assertThat(fullBody).contains("\"total_tokens\":17");
        }

        @Test
        @DisplayName("should not corrupt UTF-8 characters when split across network chunks")
        void shouldNotCorruptUtf8SplitChunks() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(ChatFixtures.RESPONSE_STREAMING_UTF8).streaming(true)
                    .utf8SplitChunks(true).chunkDelay(Duration.ofMillis(10)).build());

            byte[] rawBytes = webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_WITH_UTF8)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            assertThat(rawBytes).isEqualTo(ChatFixtures.RESPONSE_STREAMING_UTF8.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should not aggregate slow streaming into a complete response")
        void shouldNotAggregateSlowStreaming() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(ChatFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                    .chunkDelay(Duration.ofMillis(50)).build());

            Flux<String> responseBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_STREAMING).exchange().expectStatus().isOk()
                    .returnResult(String.class).getResponseBody();

            StepVerifier.create(responseBody).expectNextCount(1).expectNextCount(1).thenConsumeWhile(v -> true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should preserve unknown event fields in SSE")
        void shouldPreserveUnknownFieldsInSse() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ChatFixtures.RESPONSE_STREAMING_UNKNOWN_FIELDS).streaming(true).build());

            byte[] fullBody = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            assertThat(fullBody)
                    .isEqualTo(ChatFixtures.RESPONSE_STREAMING_UNKNOWN_FIELDS.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("Client cancellation")
    class Cancellation {

        @Test
        @DisplayName("should close the upstream connection after client cancellation")
        void shouldPropagateClientCancellation() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(ChatFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                    .chunkDelay(Duration.ofMillis(100)).build());
            Mono<Void> upstreamCancellation = mockProvider.cancellationSignal();

            Flux<org.springframework.core.io.buffer.DataBuffer> responseBody = WebClient
                    .create("http://localhost:" + gatewayPort).post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                    .bodyValue(ChatFixtures.REQUEST_STREAMING).exchangeToFlux(
                            response -> response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class));

            StepVerifier.create(responseBody).consumeNextWith(DataBufferUtils::release).thenCancel()
                    .verify(Duration.ofSeconds(15));

            StepVerifier.create(upstreamCancellation).expectComplete().verify(Duration.ofSeconds(10));
            assertThat(mockProvider.wasUpstreamCancelled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Special response content")
    class SpecialResponse {

        @Test
        @DisplayName("should proxy tool call response with finish_reason")
        void shouldProxyToolCallResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_TOOL_CALL).build());

            byte[] body = webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_WITH_TOOLS)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("\"tool_calls\"");
            assertThat(text).contains("get_weather");
            assertThat(text).contains("\"finish_reason\":\"tool_calls\"");
        }

        @Test
        @DisplayName("should proxy reasoning_content in non-streaming response")
        void shouldProxyReasoningContent() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_REASONING).build());

            byte[] body = webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("reasoning_content");
            assertThat(text).contains("56,088");
        }

        @Test
        @DisplayName("should proxy length finish_reason response")
        void shouldProxyLengthFinishReason() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_LENGTH_STOP).build());

            byte[] body = webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("\"finish_reason\":\"length\"");
        }

        @Test
        @DisplayName("should proxy tool result follow-up request")
        void shouldProxyToolResultRequest() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_WITH_TOOL_RESULT).exchange()
                    .expectStatus().isOk().expectBody().returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).bodyBytes)
                    .isEqualTo(ChatFixtures.REQUEST_WITH_TOOL_RESULT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("Path allowlisting")
    class PathAllowlisting {

        @Test
        @DisplayName("should reject unsupported path without contacting upstream")
        void shouldRejectUnsupportedPath() {
            byte[] errorBody = webTestClient.post().uri("/v1/unknown").bodyValue("{\"test\":true}").exchange()
                    .expectStatus().isNotFound().expectBody().returnResult().getResponseBody();

            assertThat(errorBody).isNotNull();
            String body = new String(errorBody, StandardCharsets.UTF_8);
            assertThat(body).contains("unsupported_path");

            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should reject GET on allowed path with OpenAI-compatible error")
        void shouldRejectGetOnAllowedPath() {
            byte[] errorBody = webTestClient.get().uri("/v1/chat/completions").exchange().expectStatus()
                    .isEqualTo(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED).expectBody().returnResult()
                    .getResponseBody();

            assertThat(errorBody).isNotNull();
            String body = new String(errorBody, StandardCharsets.UTF_8);
            assertThat(body).contains("\"error\":{");
            assertThat(body).contains("method_not_allowed");
            // OpenAI Chat path uses OpenAI-compatible errors
            assertThat(body).doesNotContain("\"type\":\"error\"");
            // no {"type":"error"} wrapper

            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Header stripping — kernel-level guarantees
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Header stripping")
    class HeaderStripping {

        @Test
        @DisplayName("should strip Connection-nominated headers from upstream request")
        void shouldStripConnectionNominatedHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/chat/completions").header("Connection", "x-nominated-header")
                    .header("x-nominated-header", "should-be-stripped").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            var req = captured.get(0);
            assertThat(req.header("Connection")).isNull();
            assertThat(req.header("x-nominated-header")).isNull();
        }

        @Test
        @DisplayName("should strip forged X-MiQroKey-* tracking headers")
        void shouldStripForgedTrackingHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/chat/completions").header("x-miqrokey-request-id", "forged-chat-id")
                    .header("X-MiQroKey-trace-id", "forged-chat-trace").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            var req = captured.get(0);
            assertThat(req.header("x-miqrokey-request-id")).isNull();
            assertThat(req.header("X-MiQroKey-trace-id")).isNull();
        }
    }

    @Nested
    @DisplayName("SSE sensitive content privacy")
    class Privacy {

        @Test
        @DisplayName("should not retain model content in SSE usage observations")
        void shouldNotRetainModelContentInObservations() {
            String modelContent = "SENSITIVE_CHAT_PRIVACY_CONTENT";
            String sseWithContent = "data: {\"id\":\"chatcmpl-pvt\",\"choices\":[{\"delta\":{\"content\":\""
                    + modelContent + "\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,"
                    + "\"total_tokens\":8}}\r\n\r\n";
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(sseWithContent).streaming(true).build());

            webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_STREAMING).exchange()
                    .expectStatus().isOk().expectBody().returnResult().getResponseBody();

            var usageObs = new SseUsageObserver();
            usageObs.wrap(
                    reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                            .wrap(sseWithContent.getBytes(StandardCharsets.UTF_8))))
                    .blockLast();
            assertThat(usageObs.getObservations()).hasSize(1);
            assertThat(usageObs.getObservations().toString()).doesNotContain(modelContent);
        }
    }
}
