package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.domain.usage.RequestCompletedEvent;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.queue.InMemoryUsageEventBus;
import com.miqroera.miqrokey.queue.UsageEventBus;
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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "logging.level.com.miqroera.miqrokey.gateway.proxy=DEBUG"})
@AutoConfigureWebTestClient
@Import(GatewayAuthTestConfig.class)
@DisplayName("OpenAI Chat Completions transparent proxy contract")
class ChatProxyContractTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UsageEventBus usageEventBus;

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

    @Nested
    @DisplayName("Usage fact guard (model-less bodies)")
    class UsageFactGuard {

        @Test
        @DisplayName("should forward a body without a model field verbatim but record no usage fact")
        void shouldForwardModelLessBodyWithoutUsageFact() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            InMemoryUsageEventBus bus = (InMemoryUsageEventBus) usageEventBus;
            bus.clear();

            String modelLessBody = "{\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"max_tokens\":512}";
            EntityExchangeResult<byte[]> proxied = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(modelLessBody).exchange().expectStatus().isOk().expectBody().returnResult();
            String requestId = requestIdOf(proxied);

            // Transparent proxy: the body still reaches upstream byte-identically.
            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).bodyBytes).isEqualTo(modelLessBody.getBytes(StandardCharsets.UTF_8));

            // usage_event.model_id is NOT NULL: a model-less request records no usage
            // fact. The barrier names *this* request — by the id the gateway minted
            // and echoed back — rather than "some terminal record arrived" (#1163).
            // This bus is a context singleton whose accessors drain the queue on
            // read, so a neighbouring request's record can land after this test's
            // clear() and satisfy a "not empty" barrier by itself: CI then saw
            // `modelId` come back as gpt-4o-mini on a request that had no model.
            RequestCompletedEvent terminal = awaitTerminal(bus, requestId);
            assertThat(terminal.modelId()).as("a model-less body records a null model").isNull();
            assertThat(usageFor(bus, requestId)).as("a model-less body records no usage fact").isEmpty();
        }

        @Test
        @DisplayName("should forward an unparseable body verbatim but record no usage fact")
        void shouldForwardMalformedBodyWithoutUsageFact() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            InMemoryUsageEventBus bus = (InMemoryUsageEventBus) usageEventBus;
            bus.clear();

            String malformedBody = "{not json";
            EntityExchangeResult<byte[]> proxied = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(malformedBody).exchange().expectStatus().isOk().expectBody().returnResult();
            String requestId = requestIdOf(proxied);

            var captured = mockProvider.getCapturedRequests();
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).bodyBytes).isEqualTo(malformedBody.getBytes(StandardCharsets.UTF_8));
            // Same barrier-by-this-request's-id as above: an unparseable body has no
            // model either, so a neighbouring record must not stand in for it either.
            awaitTerminal(bus, requestId);
            assertThat(usageFor(bus, requestId)).isEmpty();
        }

        @Test
        @DisplayName("should still record the usage fact for a well-formed request (control)")
        void shouldRecordUsageFactForWellFormedRequest() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            InMemoryUsageEventBus bus = (InMemoryUsageEventBus) usageEventBus;
            bus.clear();

            EntityExchangeResult<byte[]> proxied = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult();
            String requestId = requestIdOf(proxied);

            // Asserted on this request's own fact, fetched by its id — the same
            // barrier discipline as the model-less cases above (#1163). The wait is
            // `awaitUsage`, not a snapshot: a positive assertion has to wait for the
            // writer, or it races it.
            assertThat(awaitUsage(bus, requestId).modelId()).isEqualTo("gpt-4o-mini");
            assertThat(usageFor(bus, requestId)).as("exactly one usage fact for this request").hasSize(1);
        }

        @Test
        @DisplayName("records the provider request id from the response body when no id header exists (#623)")
        void shouldRecordProviderRequestIdFromResponseBody() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json")
                    .body("{\"id\":\"prov-body-42\",\"object\":\"chat.completion\",\"model\":\"gpt-4o-mini\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
                            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}")
                    .build());
            InMemoryUsageEventBus bus = (InMemoryUsageEventBus) usageEventBus;
            bus.clear();

            EntityExchangeResult<byte[]> proxied = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult();
            String requestId = requestIdOf(proxied);

            // The usage fact is published at response completion — an id resolved
            // only in the terminal doFinally stage would land too late here.
            assertThat(awaitUsage(bus, requestId).providerRequestId()).isEqualTo("prov-body-42");
            assertThat(usageFor(bus, requestId)).as("exactly one usage fact for this request").hasSize(1);
        }

        @Test
        @DisplayName("an id response header wins over the body id (#623)")
        void headerIdWinsOverBodyId() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").header("x-request-id", "hdr-1")
                    .body("{\"id\":\"prov-body-42\",\"object\":\"chat.completion\",\"model\":\"gpt-4o-mini\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
                            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}")
                    .build());
            InMemoryUsageEventBus bus = (InMemoryUsageEventBus) usageEventBus;
            bus.clear();

            EntityExchangeResult<byte[]> proxied = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk().expectBody()
                    .returnResult();
            String requestId = requestIdOf(proxied);

            assertThat(awaitUsage(bus, requestId).providerRequestId()).isEqualTo("hdr-1");
            assertThat(usageFor(bus, requestId)).as("exactly one usage fact for this request").hasSize(1);
        }
    }

    /**
     * The request id the gateway minted for this request, as echoed on the proxied
     * response ({@code ProxyController} sets {@code X-MiqroKey-Request-Id} on every
     * upstream response, streaming or not).
     *
     * <p>
     * This is how a test names its own request. The bus is a context singleton, so
     * "something arrived" is not the same fact as "my request's record arrived"
     * (#1163) — and the id is the only handle that distinguishes them.
     * </p>
     *
     * <p>
     * Boundary: the header is set on the proxied <em>upstream</em> response, so it
     * is absent from gateway-authored rejections and errors (401/403/404/413/502 —
     * the {@code writeError} paths). Calling this on one of those fails the
     * {@code isNotBlank} assertion on purpose; a test asserting an error path has
     * no lifecycle record to correlate anyway.
     * </p>
     */
    private static String requestIdOf(EntityExchangeResult<byte[]> proxied) {
        String requestId = proxied.getResponseHeaders().getFirst("X-MiqroKey-Request-Id");
        assertThat(requestId).as("the gateway echoes its request id on the proxied response").isNotBlank();
        return requestId;
    }

    /** Waits for — and returns — this request's terminal lifecycle record. */
    private static RequestCompletedEvent awaitTerminal(InMemoryUsageEventBus bus, String requestId) {
        return awaitValue(() -> bus.completedEvents().stream()
                .filter(record -> requestId.equals(record.gatewayRequestId())).findFirst().orElse(null),
                "this request's terminal lifecycle record");
    }

    /**
     * Waits for — and returns — this request's usage fact.
     *
     * <p>
     * A waiting read, not a snapshot: a <em>positive</em> assertion about this
     * request's usage (its model, its provider id) races the writer otherwise.
     * {@link #usageFor} is for the negative case only.
     * </p>
     */
    private static UsageEvent awaitUsage(InMemoryUsageEventBus bus, String requestId) {
        return awaitValue(() -> bus.usageEvents().stream().filter(event -> requestId.equals(event.gatewayRequestId()))
                .findFirst().orElse(null), "this request's usage fact");
    }

    /**
     * This request's usage facts as they stand right now — a snapshot, no waiting.
     *
     * <p>
     * Two sound uses. <b>Asserting there are none</b>: behind
     * {@link #awaitTerminal}, which is a barrier for it because usage is offered in
     * the response supplier (after the body is written) and the terminal record in
     * the enclosing {@code doFinally} — two successive stages of the reactive
     * chain, not one. <b>Counting</b> ({@code hasSize(1)}): only after
     * {@link #awaitUsage} has returned; the wait guarantees the first fact and the
     * snapshot then pins how many were visible at that instant. A duplicate offered
     * after the snapshot is not seen — an inherent limit of that pair, and the
     * pre-#1163 window was the same.
     * </p>
     *
     * <p>
     * Boundary: {@link #awaitTerminal} fires only for a request that reached the
     * upstream. A coalescer waiter gets a usage fact but no terminal record of its
     * own, so neither helper gives it a barrier — moot while the coalescer is off
     * and this file uses no cache key, but a coalescer test would need its own.
     * </p>
     *
     * <p>
     * For any other positive assertion, use {@link #awaitUsage}.
     * </p>
     */
    private static List<UsageEvent> usageFor(InMemoryUsageEventBus bus, String requestId) {
        return bus.usageEvents().stream().filter(event -> requestId.equals(event.gatewayRequestId())).toList();
    }

    /**
     * Polls for a value up to 5s; fails loudly instead of racing the writer.
     *
     * <p>
     * Use it when the assertion is about a <em>specific</em> record rather than
     * about "something arrived": the probe must name the fact being asserted, so a
     * record belonging to another request cannot satisfy it — which is the point
     * here, because the bus is a context singleton and the accessors drain its
     * queue on read (#1163).
     * </p>
     *
     * <p>
     * The probe signals "not yet" with {@code null}, so it must never legitimately
     * produce a null value.
     * </p>
     */
    private static <T> T awaitValue(java.util.function.Supplier<T> probe, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }
}
