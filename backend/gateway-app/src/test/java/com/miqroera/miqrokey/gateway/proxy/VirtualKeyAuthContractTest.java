package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication and authorization contract of the gateway hot path: uniform
 * 401/404/403 failure semantics, credential header hygiene, model
 * allow-listing, {@code /v1/models}, and opt-in L1 caching (ADR-0008).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false", "miqrokey.cache.enabled=true",
        "spring.main.web-application-type=reactive"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("Virtual key authentication contract")
class VirtualKeyAuthContractTest {

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
        @DisplayName("should reject a valid key presented under the wrong label")
        void shouldRejectWrongTag() {
            // DEFAULT_KEY is bound to tag "demo-proj"; presenting it under a
            // different label must be indistinguishable from an unknown key.
            String wrongTag = GatewayTestKeys.DEFAULT_KEY.presented().replace(GatewayTestKeys.PROJECT_TAG,
                    "someone-else");
            byte[] body = webTestClient.post().uri("/v1/chat/completions").header("Authorization", "Bearer " + wrongTag)
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isNotFound().expectBody()
                    .returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("virtual_key_invalid");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should not reveal whether a key exists, is malformed, or is mislabeled")
        void shouldBeIndistinguishable() {
            String malformed = new String(webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer garbage").bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange()
                    .expectStatus().isNotFound().expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8);
            String unknown = new String(webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + GatewayTestKeys.UNKNOWN_KEY.presented())
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isNotFound().expectBody()
                    .returnResult().getResponseBody(), StandardCharsets.UTF_8);
            String wrongTag = new String(webTestClient.post().uri("/v1/chat/completions").header("Authorization",
                    "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented().replace(GatewayTestKeys.PROJECT_TAG, "x-other"))
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus().isNotFound().expectBody()
                    .returnResult().getResponseBody(), StandardCharsets.UTF_8);

            assertThat(unknown).isEqualTo(malformed);
            assertThat(wrongTag).isEqualTo(malformed);
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
            List<String> ids = root.path("data").findValuesAsText("id");
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
            return OBJECT_MAPPER.readTree(body).path("data").findValuesAsText("id");
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
        void shouldServeL1Hit() {
            mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                    .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());

            byte[] first = webTestClient.post().uri("/v1/chat/completions")
                    .header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(ChatFixtures.REQUEST_NON_STREAMING)
                    .exchange().expectStatus().isOk().expectHeader()
                    .valueEquals(SseReplayEngine.X_MIQROKEY_CACHE, "miss").expectBody().returnResult()
                    .getResponseBody();

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
        @DisplayName("semantic key: different histories with the same last user message still hit")
        void semanticKeyHitsAcrossHistories() {
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
