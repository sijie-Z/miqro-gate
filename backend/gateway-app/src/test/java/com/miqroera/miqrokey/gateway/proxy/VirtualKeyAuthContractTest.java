package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.cache.CachedResponse;
import com.miqroera.miqrokey.cache.CaffeineCacheProvider;
import com.miqroera.miqrokey.cache.GatewayResponseCache;
import com.miqroera.miqrokey.cache.NoopCacheProvider;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.ChatFixtures;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication and authorization contract of the gateway hot path: uniform
 * 401/404/403 failure semantics, credential header hygiene, model
 * allow-listing, {@code /v1/models}, and opt-in L1 caching (ADR-0008).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false", "miqrokey.cache.enabled=true",
        "spring.main.web-application-type=reactive"})
@AutoConfigureWebTestClient
@Import({GatewayAuthTestConfig.class, VirtualKeyAuthContractTest.CacheIoProbeConfig.class})
@DisplayName("Virtual key authentication contract")
class VirtualKeyAuthContractTest {

    /**
     * #444: cache I/O must never run on a Reactor Netty event loop. This probe
     * replaces the context's cache bean with a recording wrapper around the same
     * Caffeine L1 (noop L2) so tests can assert the executing threads and await the
     * asynchronous fill.
     */
    @TestConfiguration
    static class CacheIoProbeConfig {

        static final Set<String> CACHE_IO_THREADS = ConcurrentHashMap.newKeySet();
        static final AtomicInteger PUT_COUNT = new AtomicInteger();

        @Bean
        @Primary
        GatewayResponseCache cacheIoProbe() {
            GatewayResponseCache inner = new CaffeineCacheProvider(new NoopCacheProvider(), Duration.ofMinutes(5));
            return new GatewayResponseCache() {

                @Override
                public Lookup get(UUID tenantId, CacheKey key) {
                    CACHE_IO_THREADS.add(Thread.currentThread().getName());
                    return inner.get(tenantId, key);
                }

                @Override
                public void put(CacheKey key, UUID tenantId, UUID virtualKeyId, UUID projectId, UUID productId,
                        String modelId, CachedResponse response) {
                    CACHE_IO_THREADS.add(Thread.currentThread().getName());
                    inner.put(key, tenantId, virtualKeyId, projectId, productId, modelId, response);
                    PUT_COUNT.incrementAndGet();
                }

                @Override
                public void invalidateProject(UUID tenantId, UUID projectId) {
                    inner.invalidateProject(tenantId, projectId);
                }

                @Override
                public long l1Size() {
                    return inner.l1Size();
                }
            };
        }
    }

    /**
     * Awaits the asynchronous cache fill issued by the previous request (#444).
     */
    private static void awaitCacheFill() throws InterruptedException {
        int before = CacheIoProbeConfig.PUT_COUNT.get();
        long deadline = System.currentTimeMillis() + 3000;
        while (CacheIoProbeConfig.PUT_COUNT.get() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    private WebTestClient.BodyContentSpec postChat(WebTestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange().expectBody();
    }

    private static String errorType(byte[] body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            return root.path("error").path("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException("unparseable error body", e);
        }
    }

    // -------------------------------------------------------------------
    // 401 — credential header problems
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("401 credential header failures")
    class CredentialFailures {

        @Test
        @DisplayName("should reject a request with no credential header")
        void shouldRejectMissingCredential() {
            // Override the default fixture header with a blank value: the
            // resolver drops it and sees "no credential".
            byte[] body = webTestClient.post().uri("/v1/chat/completions").header("Authorization", "")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isUnauthorized()
                    .expectHeader().contentType("application/json").expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("unauthorized");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should reject an empty bearer token")
        void shouldRejectEmptyBearer() {
            byte[] body = webTestClient.post().uri("/v1/chat/completions").header("Authorization", "Bearer   ")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isUnauthorized()
                    .expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("unauthorized");
        }

        @Test
        @DisplayName("should reject conflicting credential headers")
        void shouldRejectConflictingCredentials() {
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                    .header("x-api-key", "sk-some-other-key").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isUnauthorized().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("unauthorized");
            // Nothing must reach the upstream.
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // 404 — uniform VIRTUAL_KEY_INVALID
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("404 uniform virtual key failures")
    class KeyFailures {

        @Test
        @DisplayName("should reject a malformed key with virtual_key_invalid")
        void shouldRejectMalformedKey() {
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer not-a-key-at-all").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isNotFound().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("virtual_key_invalid");
        }

        @Test
        @DisplayName("should reject a well-formed key that is not in the snapshot")
        void shouldRejectUnknownKey() {
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.UNKNOWN_KEY.presented())
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isNotFound().expectBody()
                    .returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("virtual_key_invalid");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("a non-matching label on a sole-binding key routes by the sole binding (CAA §4)")
        void soleBindingIgnoresCosmeticLabel() {
            // CAA (#633): the label is a legacy selector, not a boundary — a
            // key with exactly one binding resolves regardless of its suffix.
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            String otherLabel = GatewayTestKeys.DEFAULT_KEY.presented().replace(GatewayTestKeys.PROJECT_TAG,
                    "someone-else");
            webTestClient.post().uri("/v1/chat/completions").header("Authorization", "Bearer " + otherLabel)
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk();
            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("should not reveal whether a key exists or is malformed")
        void shouldBeIndistinguishable() {
            String malformed = new String(webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer garbage").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isNotFound().expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8);
            String unknown = new String(webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.UNKNOWN_KEY.presented())
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isNotFound().expectBody()
                    .returnResult().getResponseBody(), StandardCharsets.UTF_8);
            assertThat(unknown).isEqualTo(malformed);
        }
    }

    // -------------------------------------------------------------------
    // CAA request context (#633)
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("CAA request context (#633)")
    class CaaContext {

        @Test
        @DisplayName("a multi-bound key without context fails closed (400 CONTEXT_REQUIRED)")
        void multiBoundWithoutContextRequiresContext() {
            // The suffix is part of the format but names no binding of this
            // key: no claim, no suffix match, two bindings -> fail closed.
            String unmatched = GatewayTestKeys.MULTI_BOUND_KEY.presented().replace("demo-multi", "no-such-binding");
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + unmatched).bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isBadRequest().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("CONTEXT_REQUIRED");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("a project-id claim selects the binding and routes (RESOLVED_HEADER)")
        void claimSelectsBinding() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.MULTI_BOUND_KEY.presented())
                    .header("X-Miqro-Project-Id", GatewayTestKeys.OTHER_PROJECT_ID.toString())
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk();

            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
            // The claim headers never reach the upstream.
            assertThat(mockProvider.getCapturedRequests().get(0).header("X-Miqro-Project-Id")).isNull();
        }

        @Test
        @DisplayName("a claim for a project without a binding is 403 CONTEXT_NOT_ALLOWED")
        void forgedClaimIsForbidden() {
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.MULTI_BOUND_KEY.presented())
                    .header("X-Miqro-Project-Id", java.util.UUID.randomUUID().toString())
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isForbidden().expectBody()
                    .returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("CONTEXT_NOT_ALLOWED");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // 403 — model authorization
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("403 model authorization")
    class ModelAuthorization {

        @Test
        @DisplayName("should reject a model outside the key's allowlist")
        void shouldRejectDeniedModel() {
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue("{\"model\":\"" + GatewayTestKeys.MODEL_DENIED + "\",\"messages\":[{\"role\":\"user\","
                            + "\"content\":\"hi\"}]}")
                    .exchange().expectStatus().isForbidden().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("model_not_allowed");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("a control character in the model name still yields a parseable error body")
        void controlCharacterInModelNameKeepsErrorBodyParseable() throws Exception {
            // A client can smuggle any U+0000-U+001F into the echoed model name
            // with a JSON unicode escape. The escape is spelled here as
            // backslash + "u0008" (rather than a Java escape) so this test
            // itself stays free of raw control characters.
            String backspace = String.valueOf((char) 92) + "u0008";
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .bodyValue("{\"model\":\"denied" + backspace + "model\",\"messages\":[{\"role\":\"user\","
                            + "\"content\":\"hi\"}]}")
                    .exchange().expectStatus().isForbidden().expectBody().returnResult().getResponseBody();

            JsonNode parsed = new ObjectMapper().readTree(body);
            assertThat(parsed.path("error").path("type").asText()).isEqualTo("model_not_allowed");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should allow every model in the key's allowlist")
        void shouldAllowAllowedModels() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            for (String model : GatewayTestKeys.MODELS_ALLOWED) {
                webTestClient.post().uri("/v1/chat/completions")
                        .bodyValue(
                                "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                        .exchange().expectStatus().isOk();
            }
            // All allowlisted models passed; upstream saw each request.
            assertThat(mockProvider.getCapturedRequests()).hasSize(GatewayTestKeys.MODELS_ALLOWED.size());
        }
    }

    // -------------------------------------------------------------------
    // /v1/models — per-key model listing
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("/v1/models listing")
    class ModelsListing {

        @Test
        @DisplayName("should return the four-way intersection (catalog ∩ upstream ∩ grant ∩ key), sorted")
        void shouldListAllowedModels() throws Exception {
            byte[] body = webTestClient.get().uri("/v1/models").exchange().expectStatus().isOk().expectHeader()
                    .contentType("application/json").expectBody().returnResult().getResponseBody();

            JsonNode root = OBJECT_MAPPER.readTree(body);
            assertThat(root.path("object").asText()).isEqualTo("list");
            List<String> ids = root.path("data").findValuesAsString("id");
            List<String> expected = GatewayTestKeys.MODELS_ALLOWED.stream().sorted().toList();
            assertThat(ids).isEqualTo(expected);
        }

        @Test
        @DisplayName("should exclude a model the key's grant does not authorize")
        void shouldExcludeGrantDeniedModel() throws Exception {
            List<String> ids = listModels(GatewayTestKeys.GRANT_LIMITED_KEY);
            assertThat(ids).doesNotContain(GatewayTestKeys.MODEL_GRANT_DENIED);
            assertThat(ids).hasSize(GatewayTestKeys.MODELS_ALLOWED.size() - 1);
        }

        @Test
        @DisplayName("should exclude a model the upstream catalog has never seen")
        void shouldExcludeUpstreamDeniedModel() throws Exception {
            List<String> ids = listModels(GatewayTestKeys.UPSTREAM_LIMITED_KEY);
            assertThat(ids).doesNotContain(GatewayTestKeys.MODEL_UPSTREAM_DENIED);
            assertThat(ids).hasSize(GatewayTestKeys.MODELS_ALLOWED.size() - 1);
        }

        @Test
        @DisplayName("should return an empty list when no upstream fetch has ever succeeded")
        void shouldReturnEmptyWithoutUpstreamModels() throws Exception {
            assertThat(listModels(GatewayTestKeys.NO_UPSTREAM_KEY)).isEmpty();
        }

        @Test
        @DisplayName("should return an empty list for a product unknown to the signed catalog")
        void shouldReturnEmptyForUnknownProduct() throws Exception {
            assertThat(listModels(GatewayTestKeys.UNKNOWN_PRODUCT_KEY)).isEmpty();
        }

        @Test
        @DisplayName("should reject /v1/models with an invalid key")
        void shouldRejectInvalidKey() {
            byte[] body = webTestClient.get().uri("/v1/models")
                    .header("Authorization", "Bearer " + GatewayTestKeys.UNKNOWN_KEY.presented()).exchange()
                    .expectStatus().isNotFound().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("virtual_key_invalid");
        }

        /**
         * Lists the models served to the given key, overriding the default fixture
         * header.
         */
        private List<String> listModels(GatewayTestKeys.KeyFixture key) throws Exception {
            byte[] body = webTestClient.get().uri("/v1/models").header("Authorization", "Bearer " + key.presented())
                    .exchange().expectStatus().isOk().expectHeader().contentType("application/json").expectBody()
                    .returnResult().getResponseBody();
            return OBJECT_MAPPER.readTree(body).path("data").findValuesAsString("id");
        }
    }

    // -------------------------------------------------------------------
    // Opt-in L1 caching (ADR-0008)
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Opt-in L1 caching")
    class L1Caching {

        @Test
        @DisplayName("should serve a byte-identical L1 hit for an identical cacheable request")
        void shouldServeL1Hit() throws InterruptedException {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            byte[] first = webTestClient.post().uri("/v1/chat/completions")
                    .header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss").expectBody().returnResult()
                    .getResponseBody();
            awaitCacheFill();

            byte[] second = webTestClient.post().uri("/v1/chat/completions")
                    .header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectHeader().valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "L1")
                    .expectBody().returnResult().getResponseBody();

            assertThat(second).isEqualTo(first);
            assertThat(second).isEqualTo(ChatFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
            // The second request must never reach the upstream.
            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("stream format is part of the key: SSE never replays into a JSON request (#444)")
        void streamFormatIsPartOfTheKey() {
            // Prime the cache with a streaming request (SSE response gets stored).
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("text/event-stream").body(ChatFixtures.RESPONSE_STREAMING_SSE).build());
            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(ChatFixtures.REQUEST_STREAMING).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");

            // Same system prompt + last user message, but a non-streaming request:
            // it must MISS (different format) and go upstream for a JSON answer —
            // replaying the stored SSE frames to a JSON client would break parsing.
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            byte[] body = webTestClient.post().uri("/v1/chat/completions")
                    .header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":"
                            + "\"Tell me a short story.\"}],\"max_tokens\":512}")
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss").expectBody().returnResult()
                    .getResponseBody();
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(ChatFixtures.RESPONSE_BASIC);
        }

        @Test
        @DisplayName("wire protocol is part of the key: a chat-cached answer never replays into /v1/messages (#1236)")
        void wireProtocolIsPartOfTheKey() throws InterruptedException {
            // Prime the cache through the OpenAI chat endpoint. The body is a
            // plain chat body whose semantic scope (system + last user message)
            // is extracted from the bytes alone, so the same bytes sent to a
            // different endpoint used to produce one shared cache key (#1236).
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            String body = """
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1236 cross protocol"}]}""";
            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(body).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            awaitCacheFill();

            // The same bytes through the Anthropic endpoint: the wire protocol
            // decides the response shape — #444's argument one level up — so
            // this must MISS and fetch its own Anthropic-shaped answer.
            // Replaying the OpenAI-shaped first response would hand an
            // Anthropic client a body it cannot parse.
            String anthropicShaped = "{\"id\":\"msg_1236\",\"type\":\"message\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],\"stop_reason\":\"end_turn\"}";
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(anthropicShaped).build());
            byte[] second = webTestClient.post().uri("/v1/messages").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(body).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss").expectBody().returnResult()
                    .getResponseBody();

            assertThat(new String(second, StandardCharsets.UTF_8)).isEqualTo(anthropicShaped);
            assertThat(mockProvider.getCapturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("wire protocol is part of the key for /v1/responses: a responses-cached answer never replays into chat (#1421)")
        void responsesProtocolIsPartOfTheKey() throws InterruptedException {
            // Prime the cache through /v1/responses — the third production
            // mapping (#1236) previously had no HTTP-level assertion. The body
            // is not a chat shape, so the key falls back to the full normalized
            // body; only the family dimension separates it from the chat key.
            String responsesShaped = "{\"id\":\"resp_1421\",\"object\":\"response\",\"status\":\"completed\","
                    + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":"
                    + "[{\"type\":\"output_text\",\"text\":\"pong\"}]}]}";
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(responsesShaped).build());
            String body = """
                    {"model":"gpt-4o-mini","input":"cache probe 1421 responses cross protocol"}""";
            webTestClient.post().uri("/v1/responses").header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(body)
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            awaitCacheFill();

            // The same bytes through the OpenAI chat endpoint: the wire protocol
            // decides the response shape, so this must MISS and fetch its own
            // chat-shaped answer. Replaying the Responses-shaped first response
            // would hand a chat client a body it cannot parse.
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            byte[] second = webTestClient.post().uri("/v1/chat/completions")
                    .header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(body).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss").expectBody().returnResult()
                    .getResponseBody();

            assertThat(new String(second, StandardCharsets.UTF_8)).isEqualTo(ChatFixtures.RESPONSE_BASIC);
            assertThat(mockProvider.getCapturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("same endpoint still hits: the protocol dimension does not disable the cache (#1236)")
        void sameProtocolStillHits() throws InterruptedException {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            String body = """
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1236 same protocol"}]}""";
            webTestClient.post().uri("/v1/messages").header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(body)
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            awaitCacheFill();

            // The same bytes to the same endpoint: still an L1 hit — the new
            // key dimension must not cost the cache its hits (#1236 guard).
            webTestClient.post().uri("/v1/messages").header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(body)
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "L1");

            // Exactly one upstream exchange: the second request was served from
            // the cache.
            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("same /v1/responses endpoint still hits: the protocol dimension does not disable the cache there (#1421)")
        void responsesSameProtocolStillHits() throws InterruptedException {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            String body = """
                    {"model":"gpt-4o-mini","input":"cache probe 1421 responses same protocol"}""";
            webTestClient.post().uri("/v1/responses").header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(body)
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            awaitCacheFill();

            // The same bytes to the same endpoint: still an L1 hit — the family
            // dimension must not cost the responses endpoint its cache hits
            // (the #1236 guard, now proven on the third mapping too).
            webTestClient.post().uri("/v1/responses").header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(body)
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "L1");

            // Exactly one upstream exchange: the second request was served from
            // the cache.
            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("cache I/O runs on the bounded scheduler, never on the event loop (#444)")
        void cacheIoRunsOffTheEventLoop() throws Exception {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
            // Unique content so this request's key cannot be pre-filled by others.
            String unique = """
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"thread probe 444"}]}""";

            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(unique).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            awaitCacheFill();

            assertThat(CacheIoProbeConfig.CACHE_IO_THREADS).isNotEmpty();
            // Event loops are named webflux-http-nio-N (server) / reactor-http-nio-N;
            // the bounded scheduler threads carry our own prefix.
            assertThat(CacheIoProbeConfig.CACHE_IO_THREADS)
                    .allSatisfy(name -> assertThat(name).doesNotContain("webflux-http").doesNotContain("reactor-http"));
        }

        @Test
        @DisplayName("semantic key: different histories with the same last user message still hit")
        void semanticKeyHitsAcrossHistories() throws InterruptedException {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            // Earlier turns differ; the last user message and the system prompt
            // are identical — the semantic cache key (F02, aligned with Tencent's
            // "latest user message") must hit. The final question text is unique
            // to this test so no other scenario can pre-populate its key.
            String first = """
                    {"model":"gpt-4o-mini","messages":[
                      {"role":"user","content":"earlier question about project A"},
                      {"role":"user","content":"What time is it in Beijing exactly?"}]}""";
            String second = """
                    {"model":"gpt-4o-mini","messages":[
                      {"role":"user","content":"earlier question about project B"},
                      {"role":"user","content":"What time is it in Beijing exactly?"}]}""";

            byte[] hit = webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(first).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss").expectBody().returnResult()
                    .getResponseBody();
            awaitCacheFill();

            byte[] replayed = webTestClient.post().uri("/v1/chat/completions")
                    .header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(second).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "L1").expectBody().returnResult()
                    .getResponseBody();

            assertThat(replayed).isEqualTo(hit);
            assertThat(replayed).isEqualTo(ChatFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("semantic key: a different last user message misses")
        void semanticKeyMissesOnDifferentQuestion() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            String question = """
                    {"model":"gpt-4o-mini","messages":[
                      {"role":"user","content":"What time is it in Tokyo?"}]}""";
            String otherQuestion = """
                    {"model":"gpt-4o-mini","messages":[
                      {"role":"user","content":"What time is it in Paris?"}]}""";

            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(question).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(otherQuestion).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");

            // Both went upstream: different last user messages are different keys.
            assertThat(mockProvider.getCapturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("should not cache without the explicit opt-in header")
        void shouldNotCacheWithoutOptIn() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isOk().expectHeader().doesNotExist(SseReplayEngine.X_MIQROKEY_CACHE);
            webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isOk().expectHeader().doesNotExist(SseReplayEngine.X_MIQROKEY_CACHE);

            // Both requests went upstream: caching is opt-in, not automatic.
            assertThat(mockProvider.getCapturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("should not cache upstream errors")
        void shouldNotCacheErrors() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(400)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_ERROR_400).build());

            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isBadRequest()
                    .expectHeader().valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isBadRequest();

            assertThat(mockProvider.getCapturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("output-shaping knobs split the key: no replay across budgets, stop strings, logprobs (#1302)")
        void outputShapingParametersSplitTheKey() throws InterruptedException {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            // A chat body keeps only system + last user message as its key scope,
            // so every other output-shaping field must be an explicit key
            // dimension. Each pair below is identical except for one knob and
            // carries a message text unique to that pair; before #1302 both
            // members of a pair produced one key and the second request replayed
            // the first answer byte-for-byte instead of going upstream.
            int before = mockProvider.getCapturedRequests().size();
            assertSplits("""
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1302 budget"}],
                     "max_completion_tokens":16}""", """
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1302 budget"}],
                     "max_completion_tokens":4096}""");
            assertSplits("""
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1302 stop"}],
                     "stop_sequences":["</answer>"]}""", """
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1302 stop"}]}""");
            assertSplits("""
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1302 logprobs"}],
                     "logprobs":true,"top_logprobs":5}""", """
                    {"model":"gpt-4o-mini","messages":[{"role":"user","content":"cache probe 1302 logprobs"}]}""");
            assertThat(mockProvider.getCapturedRequests()).hasSize(before + 6);
        }

        /**
         * Posts both bodies with the cache opt-in header and asserts the second one is
         * a MISS with its own upstream exchange — i.e. the two bodies are different
         * cache keys.
         */
        private void assertSplits(String first, String second) throws InterruptedException {
            webTestClient.post().uri("/v1/chat/completions").header(CacheEligibility.CACHEABLE_HEADER, "1")
                    .bodyValue(first).exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss");
            awaitCacheFill();
            int before = mockProvider.getCapturedRequests().size();

            byte[] replayed = webTestClient.post().uri("/v1/chat/completions")
                    .header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(second).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss").expectBody().returnResult()
                    .getResponseBody();

            assertThat(new String(replayed, StandardCharsets.UTF_8)).isEqualTo(ChatFixtures.RESPONSE_BASIC);
            assertThat(mockProvider.getCapturedRequests()).hasSize(before + 1);
        }
    }

    // -------------------------------------------------------------------
    // Header smuggling — forged credential/hop headers never reach upstream
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Header smuggling hardening")
    class HeaderSmuggling {

        @BeforeEach
        void configureMock() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
        }

        @Test
        @DisplayName("forged credential headers are rejected before reaching upstream")
        void stripsForgedCredentialHeaders() {
            // A request carrying a valid virtual key plus any second credential
            // header (x-api-key / api-key) is refused at the auth layer with
            // 401 — forged credentials never get a chance to reach upstream.
            webTestClient.post().uri("/v1/chat/completions").header("x-api-key", "sk-attacker")
                    .header("api-key", "sk-attacker-2").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isUnauthorized();

            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("only the injected credential reaches upstream; the client key never leaks")
        void injectsOnlyGatewayCredential() {
            webTestClient.post().uri("/v1/chat/completions").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isOk();

            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
            AnthropicMockProvider.CapturedRequest upstream = mockProvider.getCapturedRequests().get(0);
            assertThat(upstream.headers("authorization"))
                    .containsExactly(GatewayAuthTestConfig.UPSTREAM_CREDENTIAL_VALUE);
            assertThat(upstream.headers("authorization"))
                    .noneMatch(v -> v.contains(GatewayTestKeys.DEFAULT_KEY.presented()));
        }

        @Test
        @DisplayName("Connection-nominated and X-MiQroKey-* headers are stripped")
        void stripsHopNominatedHeaders() {
            webTestClient.post().uri("/v1/chat/completions").header("Connection", "X-Remove-Me")
                    .header("X-Remove-Me", "hop-value").header("X-MiQroKey-Request-Id", "forged")
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isOk();

            assertThat(mockProvider.getCapturedRequests()).hasSize(1);
            AnthropicMockProvider.CapturedRequest upstream = mockProvider.getCapturedRequests().get(0);
            assertThat(upstream.headers("x-remove-me")).isEmpty();
            assertThat(upstream.headers("x-miqrokey-request-id")).isEmpty();
            assertThat(upstream.headers("connection")).isEmpty();
        }

        @Test
        @DisplayName("duplicate authorization headers are rejected before reaching upstream")
        void rejectsDuplicateAuthorization() {
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                    .header("Authorization", "Bearer sk-attacker").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isUnauthorized();

            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }
    }
}
