package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The #740 content-filter shadow with the feature ON and a probe vocabulary.
 *
 * <p>
 * Every test carries the byte-identity half of the contract: the forwarded
 * request bytes equal the sent bytes exactly, and the relayed reply bytes equal
 * the provider's bytes exactly (the same fixture bytes asserted by
 * {@link ContentFilterShadowDisabledTest} — enabled and disabled describe the
 * same bytes). The counter side is asserted as deltas, so test order never
 * matters; output-direction meters are awaited because they move in the
 * post-write supplier (the #770 test hit that race first).
 * </p>
 *
 * <p>
 * The vocabulary (categories used as metric tags, words case-insensitive
 * substrings): {@code alpha=Probe-A1|probe-b2;beta=Probe-C3;gamma=Probe-D4}.
 * Category alpha owns two words — the input test exercises the "one counted hit
 * per category per evaluation" rule with them — beta and gamma own one each.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.content-filter.enabled=true",
        "miqrokey.gateway.content-filter.vocabulary=alpha=Probe-A1|probe-b2;beta=Probe-C3;gamma=Probe-D4"})
@AutoConfigureWebTestClient(timeout = "30s")
@Import({GatewayAuthTestConfig.class, ContentFilterShadowIntegrationTest.ExplodingMatcherConfig.class})
@DisplayName("Content-filter shadow enabled (#740)")
class ContentFilterShadowIntegrationTest {

    private static final String EVALUATIONS = "miqrokey_gateway_content_filter_evaluations_total";
    private static final String HITS = "miqrokey_gateway_content_filter_hits_total";

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

    @BeforeEach
    void resetExplosionCounter() {
        ExplodingShadow.explosions.set(0);
    }

    @Test
    @DisplayName("an input hit is counted once per category and both directions stay byte-identical")
    void inputHitIsCountedAndBytesAreUntouched() throws InterruptedException {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
        String requestBody = "{\"model\":\"claude-sonnet-5-20250915\",\"max_tokens\":1024,\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"please handle probe-a1 and PROBE-B2, then probe-a1 again\"}]}";

        double alphaBefore = hits("input", "alpha");
        double betaBefore = hits("input", "beta");
        double outputEvaluationsBefore = evaluations("output");

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(requestBody).exchange().expectStatus()
                .isOk().expectBody().returnResult().getResponseBody();

        // Byte-identity: the reply is the provider's reply, byte for byte, and the
        // provider received exactly the bytes the client sent.
        assertThat(response).isEqualTo(AnthropicFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        var captured = mockProvider.getCapturedRequests();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).bodyBytes).isEqualTo(requestBody.getBytes(StandardCharsets.UTF_8));
        // One counted hit per category — the two distinct alpha words (one of them
        // repeated) still add exactly one to alpha; beta has no matching word in
        // the request.
        assertThat(hits("input", "alpha")).isEqualTo(alphaBefore + 1);
        assertThat(hits("input", "beta")).isEqualTo(betaBefore);
        // The reply was evaluated as well (it carries no vocabulary word).
        assertThat(await(outputEvaluationsBefore + 1, () -> evaluations("output")))
                .isEqualTo(outputEvaluationsBefore + 1);
    }

    @Test
    @DisplayName("an output hit is counted and the reply is still byte-identical")
    void outputHitIsCountedAndReplyIsUntouched() throws InterruptedException {
        String reply = "{\"id\":\"msg_cf01\",\"type\":\"message\",\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"the reply carries probe-c3 inside\"}],"
                + "\"model\":\"claude-sonnet-5-20250915\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":7,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}";
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(reply).build());

        double betaBefore = hits("output", "beta");
        double alphaBefore = hits("output", "alpha");

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

        assertThat(response).isEqualTo(reply.getBytes(StandardCharsets.UTF_8));
        assertThat(mockProvider.getCapturedRequests().get(0).bodyBytes)
                .isEqualTo(AnthropicFixtures.REQUEST_NON_STREAMING.getBytes(StandardCharsets.UTF_8));
        assertThat(await(betaBefore + 1, () -> hits("output", "beta"))).isEqualTo(betaBefore + 1);
        // A category absent from the reply stays untouched.
        assertThat(hits("output", "alpha")).isEqualTo(alphaBefore);
    }

    @Test
    @DisplayName("a streaming reply is byte-identical and evaluated once")
    void streamingReplyIsEvaluatedAndUntouched() throws InterruptedException {
        String sseBody = "event: message_start\r\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_cf_sse\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5-20250915\",\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\r\n\r\n"
                + "event: content_block_delta\r\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"streamed probe-d4\"}}\r\n\r\n"
                + "event: message_stop\r\ndata: {\"type\":\"message_stop\"}\r\n\r\n";
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("text/event-stream").body(sseBody).streaming(true).build());

        double gammaBefore = hits("output", "gamma");
        double evaluationsBefore = evaluations("output");

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_STREAMING)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

        assertThat(response).isEqualTo(sseBody.getBytes(StandardCharsets.UTF_8));
        assertThat(await(gammaBefore + 1, () -> hits("output", "gamma"))).isEqualTo(gammaBefore + 1);
        assertThat(await(evaluationsBefore + 1, () -> evaluations("output"))).isEqualTo(evaluationsBefore + 1);
    }

    @Test
    @DisplayName("an evaluation without hits leaves every hit counter alone")
    void noHitEvaluationMovesOnlyTheEvaluationsCounter() throws InterruptedException {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());

        double inputEvaluationsBefore = evaluations("input");
        double outputEvaluationsBefore = evaluations("output");
        double alphaInputBefore = hits("input", "alpha");
        double gammaOutputBefore = hits("output", "gamma");

        webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectStatus().isOk();

        assertThat(evaluations("input")).isEqualTo(inputEvaluationsBefore + 1);
        // Await the output evaluation first, so the zero-hit assertions below are
        // made after it ran rather than before it started.
        assertThat(await(outputEvaluationsBefore + 1, () -> evaluations("output")))
                .isEqualTo(outputEvaluationsBefore + 1);
        assertThat(hits("input", "alpha")).isEqualTo(alphaInputBefore);
        assertThat(hits("output", "gamma")).isEqualTo(gammaOutputBefore);
    }

    @Test
    @DisplayName("a throwing matcher is swallowed: the request still succeeds byte-identically")
    void explodingMatcherDoesNotBreakThePipeline() throws InterruptedException {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
        String requestBody = "{\"model\":\"claude-sonnet-5-20250915\",\"max_tokens\":1024,\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"cf-shadow-explode-test\"}]}";

        double evaluationsBefore = evaluations("input");
        double outputEvaluationsBefore = evaluations("output");

        byte[] response = webTestClient.post().uri("/v1/messages").bodyValue(requestBody).exchange().expectStatus()
                .isOk().expectBody().returnResult().getResponseBody();

        // The injected failure really fired (not a vacuous pass)...
        assertThat(ExplodingShadow.explosions.get()).isEqualTo(1);
        // ... and was swallowed: 200, exact reply, exact forwarded bytes, and the
        // liveness counter still moved.
        assertThat(response).isEqualTo(AnthropicFixtures.RESPONSE_BASIC.getBytes(StandardCharsets.UTF_8));
        assertThat(mockProvider.getCapturedRequests().get(0).bodyBytes)
                .isEqualTo(requestBody.getBytes(StandardCharsets.UTF_8));
        assertThat(evaluations("input")).isEqualTo(evaluationsBefore + 1);
        // Await the reply-side evaluation too, so this test leaves nothing in
        // flight for the next one's counter snapshots.
        assertThat(await(outputEvaluationsBefore + 1, () -> evaluations("output")))
                .isEqualTo(outputEvaluationsBefore + 1);
    }

    /**
     * Failure injection: the matcher explodes for one marker string, and the
     * swallow in the real class must keep the request green end to end. The marker
     * is not a vocabulary word, so every other test in this class runs the genuine
     * matcher through the delegate.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExplodingMatcherConfig {

        @Bean
        @Primary
        ContentFilterShadow explodingContentFilterShadow(ContentFilterProperties properties, MeterRegistry registry) {
            return new ExplodingShadow(properties, registry);
        }
    }

    private static final class ExplodingShadow extends ContentFilterShadow {

        static final String MARKER = "cf-shadow-explode-test";
        static final AtomicInteger explosions = new AtomicInteger();

        ExplodingShadow(ContentFilterProperties properties, MeterRegistry registry) {
            super(properties, registry);
        }

        @Override
        Map<String, Integer> match(String text) {
            if (text.contains(MARKER)) {
                explosions.incrementAndGet();
                throw new IllegalStateException("deliberate test explosion");
            }
            return super.match(text);
        }
    }

    private double evaluations(String direction) {
        var counter = meterRegistry.find(EVALUATIONS).tag("direction", direction).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double hits(String direction, String category) {
        var counter = meterRegistry.find(HITS).tags("direction", direction, "category", category).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * The output-direction meters move in the post-write completion supplier: the
     * client can hold the last byte before the counter moves (the #770 observation
     * test hit the same race on the Windows runner). Poll briefly instead of
     * assuming ordering. Input-direction meters move before the upstream call, so
     * they need no polling.
     */
    private double await(double expected, Supplier<Double> value) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        double observed = value.get();
        while (observed < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
            observed = value.get();
        }
        return observed;
    }
}
