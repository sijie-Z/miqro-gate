package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.ResponsesFixtures;
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
@DisplayName("OpenAI Responses transparent proxy contract")
class ResponsesProxyContractTest {

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
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_BASIC).build());

            byte[] respBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            assertThat(respBody).isNotNull();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            var req = captured.get(0);
            assertThat(req.method).isEqualTo("POST");
            assertThat(req.path).isEqualTo("/v1/responses");
            assertThat(req.bodyBytes)
                    .isEqualTo(ResponsesFixtures.REQUEST_NON_STREAMING.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should preserve response status, headers and raw JSON body")
        void shouldPreserveResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").header("x-request-id", "resp_req_001")
                    .header("x-custom", "responses-test").body(ResponsesFixtures.RESPONSE_BASIC).build());

            byte[] responseBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals("x-request-id", "resp_req_001").expectHeader()
                    .valueEquals("x-custom", "responses-test").expectBody().returnResult().getResponseBody();

            assertThat(responseBody).isEqualTo(ResponsesFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should consume the presented key for auth and inject the real credential upstream")
        void shouldStripCredentialHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isOk().expectBody().returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            var req = captured.get(0);
            // The presented virtual key never reaches the upstream; the real
            // credential is injected into the same Authorization header instead.
            assertThat(req.header("Authorization")).isEqualTo(GatewayAuthTestConfig.UPSTREAM_CREDENTIAL_VALUE);
            assertThat(req.header("Authorization")).isNotEqualTo("Bearer " + GatewayTestKeys.DEFAULT_KEY.presented());
            // The gateway injects the real upstream credential instead.
            assertThat(req.header(GatewayAuthTestConfig.UPSTREAM_CREDENTIAL_HEADER))
                    .isEqualTo(GatewayAuthTestConfig.UPSTREAM_CREDENTIAL_VALUE);
        }

        @Test
        @DisplayName("should preserve the raw query octets and ordering")
        void shouldPreserveQueryParameters() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_BASIC).build());

            String rawTarget = "/v1/responses?path=a%2Fb&value=a+b&x=1&flag&x=2";
            HttpClient.create()
                    .headers(h -> h.set("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())).post()
                    .uri("http://localhost:" + gatewayPort + rawTarget)
                    .send(ByteBufFlux.fromString(Mono.just(ResponsesFixtures.REQUEST_NON_STREAMING)))
                    .responseSingle((response, body) -> body.asByteArray()).block(Duration.ofSeconds(10));

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured.get(0).path).isEqualTo(rawTarget);
        }

        @Test
        @DisplayName("should return upstream error status and body")
        void shouldReturnUpstreamError() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(400)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_ERROR_400).build());

            byte[] responseBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isBadRequest()
                    .expectBody().returnResult().getResponseBody();

            String body = new String(Objects.requireNonNull(responseBody), StandardCharsets.UTF_8);
            assertThat(body).contains("invalid_request_error");
        }

        @Test
        @DisplayName("should preserve non-standard upstream status 529")
        void shouldPreserveNonStandardStatus() {
            String upstreamBody = "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"Service overloaded\"}}";
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(529)
                    .contentType("application/json").body(upstreamBody).build());

            byte[] responseBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isEqualTo(529)
                    .expectBody().returnResult().getResponseBody();

            assertThat(responseBody).isEqualTo(upstreamBody.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should preserve unknown fields in request and response")
        void shouldPreserveUnknownFields() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_WITH_UNKNOWN_FIELDS).build());

            byte[] responseBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_WITH_UNKNOWN_FIELDS).exchange().expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody();

            assertThat(responseBody)
                    .isEqualTo(ResponsesFixtures.RESPONSE_WITH_UNKNOWN_FIELDS.getBytes(StandardCharsets.UTF_8));

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured.get(0).bodyBytes)
                    .isEqualTo(ResponsesFixtures.REQUEST_WITH_UNKNOWN_FIELDS.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("Streaming SSE responses")
    class Streaming {

        @Test
        @DisplayName("should proxy SSE streaming response preserving event order")
        void shouldProxySseStream() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ResponsesFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

            byte[] fullBody = webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_STREAMING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            assertThat(fullBody).isEqualTo(ResponsesFixtures.RESPONSE_STREAMING_SSE.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should proxy SSE with function call delta events")
        void shouldProxySseWithFunctionCall() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ResponsesFixtures.RESPONSE_STREAMING_FUNCTION_CALL).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_WITH_TOOLS)
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("function_call_arguments");
            assertThat(fullBody).contains("get_weather");
            assertThat(fullBody).contains("San Francisco");
        }

        @Test
        @DisplayName("should proxy SSE with reasoning item events")
        void shouldProxySseWithReasoning() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ResponsesFixtures.RESPONSE_STREAMING_REASONING).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_WITH_REASONING).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("reasoning_summary");
            assertThat(fullBody).contains("\"reasoning_tokens\":80");
        }

        @Test
        @DisplayName("should preserve usage fields in SSE")
        void shouldPreserveUsageInSse() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ResponsesFixtures.RESPONSE_STREAMING_SSE).streaming(true).build());

            String fullBody = webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_STREAMING)
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

            assertThat(fullBody).contains("\"input_tokens\":10");
            assertThat(fullBody).contains("\"output_tokens\":7");
            assertThat(fullBody).contains("\"total_tokens\":17");
        }

        @Test
        @DisplayName("should not corrupt UTF-8 characters when split across network chunks")
        void shouldNotCorruptUtf8SplitChunks() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(ResponsesFixtures.RESPONSE_STREAMING_UTF8).streaming(true)
                    .utf8SplitChunks(true).chunkDelay(Duration.ofMillis(10)).build());

            byte[] rawBytes = webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_WITH_UTF8)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            assertThat(rawBytes).isEqualTo(ResponsesFixtures.RESPONSE_STREAMING_UTF8.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("should not aggregate slow streaming into a complete response")
        void shouldNotAggregateSlowStreaming() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(ResponsesFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                    .chunkDelay(Duration.ofMillis(50)).build());

            Flux<String> responseBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_STREAMING).exchange().expectStatus().isOk()
                    .returnResult(String.class).getResponseBody();

            StepVerifier.create(responseBody).expectNextCount(1).expectNextCount(1).thenConsumeWhile(v -> true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should preserve unknown event fields in SSE")
        void shouldPreserveUnknownFieldsInSse() {
            mockProvider.configure(
                    AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("text/event-stream")
                            .body(ResponsesFixtures.RESPONSE_STREAMING_UNKNOWN_FIELDS).streaming(true).build());

            byte[] fullBody = webTestClient.post().uri("/v1/responses")
                    .bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            assertThat(fullBody)
                    .isEqualTo(ResponsesFixtures.RESPONSE_STREAMING_UNKNOWN_FIELDS.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("Client cancellation")
    class Cancellation {

        @Test
        @DisplayName("should close the upstream connection after client cancellation")
        void shouldPropagateClientCancellation() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(ResponsesFixtures.RESPONSE_STREAMING_SSE).streaming(true)
                    .chunkDelay(Duration.ofMillis(100)).build());
            Mono<Void> upstreamCancellation = mockProvider.cancellationSignal();

            Flux<org.springframework.core.io.buffer.DataBuffer> responseBody = WebClient
                    .create("http://localhost:" + gatewayPort).post().uri("/v1/responses")
                    .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                    .bodyValue(ResponsesFixtures.REQUEST_STREAMING).exchangeToFlux(
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
        @DisplayName("should proxy function call response correctly")
        void shouldProxyFunctionCallResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_FUNCTION_CALL).build());

            byte[] body = webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_WITH_TOOLS)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("\"type\":\"function_call\"");
            assertThat(text).contains("get_weather");
            assertThat(text).contains("San Francisco");
        }

        @Test
        @DisplayName("should proxy reasoning response correctly")
        void shouldProxyReasoningResponse() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_REASONING).build());

            byte[] body = webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_WITH_REASONING)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            String text = new String(Objects.requireNonNull(body), StandardCharsets.UTF_8);
            assertThat(text).contains("\"type\":\"reasoning\"");
            assertThat(text).contains("\"reasoning_tokens\":60");
        }

        @Test
        @DisplayName("should preserve function_call_output request byte-for-byte")
        void shouldProxyFunctionCallOutputRequest() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_FUNCTION_CALL_OUTPUT)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).bodyBytes)
                    .isEqualTo(ResponsesFixtures.REQUEST_FUNCTION_CALL_OUTPUT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("Path allowlisting")
    class PathAllowlisting {

        @Test
        @DisplayName("should reject unsupported path without contacting upstream")
        void shouldRejectUnsupportedPath() {
            byte[] errorBody = webTestClient.post().uri("/v1/unknown-endpoint").bodyValue("{\"test\":true}").exchange()
                    .expectStatus().isNotFound().expectBody().returnResult().getResponseBody();

            assertThat(errorBody).isNotNull();
            String body = new String(errorBody, StandardCharsets.UTF_8);
            assertThat(body).contains("unsupported_path");

            // Must not have contacted upstream
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should reject GET on allowed path with OpenAI-compatible error")
        void shouldRejectGetOnAllowedPath() {
            byte[] errorBody = webTestClient.get().uri("/v1/responses").exchange().expectStatus()
                    .isEqualTo(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED).expectBody().returnResult()
                    .getResponseBody();

            assertThat(errorBody).isNotNull();
            String body = new String(errorBody, StandardCharsets.UTF_8);
            assertThat(body).contains("\"error\":{");
            assertThat(body).contains("method_not_allowed");
            // OpenAI format — no {"type":"error"} wrapper
            assertThat(body).doesNotContain("\"type\":\"error\"");
            // OpenAI Responses path uses OpenAI-compatible errors

            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Header stripping — kernel-level guarantees shared across protocols
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Header stripping")
    class HeaderStripping {

        @Test
        @DisplayName("should strip Connection-nominated headers from upstream request")
        void shouldStripConnectionNominatedHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/responses").header("Connection", "x-nominated-header")
                    .header("x-nominated-header", "should-be-stripped")
                    .bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            var captured = mockProvider.getCapturedRequests();
            var req = captured.get(0);
            assertThat(req.header("Connection")).isNull();
            assertThat(req.header("x-nominated-header")).isNull();
        }

        @Test
        @DisplayName("should strip forged X-MiQroKey-* tracking headers")
        void shouldStripForgedTrackingHeaders() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ResponsesFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/responses").header("x-miqrokey-request-id", "forged-resp-id")
                    .header("X-MiQroKey-trace-id", "forged-resp-trace")
                    .bodyValue(ResponsesFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

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
            String modelContent = "SENSITIVE_RESPONSES_PRIVACY_CONTENT";
            String sseWithContent = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_pvt\","
                    + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\"," + "\"text\":\""
                    + modelContent + "\"}]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}}\r\n\r\n";
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(sseWithContent).streaming(true).build());

            webTestClient.post().uri("/v1/responses").bodyValue(ResponsesFixtures.REQUEST_STREAMING).exchange()
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
