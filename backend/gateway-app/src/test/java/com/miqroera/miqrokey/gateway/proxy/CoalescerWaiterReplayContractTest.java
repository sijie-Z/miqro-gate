package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0008 request coalescing (opt-in): what a <em>waiter</em> receives when
 * the leader's response is not replayable.
 *
 * <p>
 * {@code RequestCoalescer}'s contract says a waiter whose leader failed — or
 * which timed out waiting — falls back to its own upstream call. A response the
 * gateway refused to store (non-2xx, oversized, or one that references tool
 * calls) is returned to {@code forward} as a no-cache marker: an empty body
 * with {@code isComplete=false}. Replaying that marker to a waiter produces an
 * empty success response, which is not something the leader ever received.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false", "miqrokey.cache.enabled=true",
        "miqrokey.gateway.coalescer.enabled=true", "miqrokey.gateway.coalescer.wait-timeout=5s",
        "spring.main.web-application-type=reactive"})
@AutoConfigureWebTestClient
@Import(GatewayAuthTestConfig.class)
@DisplayName("Coalescer waiter replay contract")
class CoalescerWaiterReplayContractTest {

    /**
     * A complete, valid 200 response that mentions tool calls. The gateway never
     * stores such a response, so a leader returning it has nothing to share.
     */
    private static final String TOOL_CALL_BODY = "{\"id\":\"chatcmpl-coalesce\",\"object\":\"chat.completion\","
            + "\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
            + "{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4,\"total_tokens\":13}}";

    /** A non-2xx upstream reply: streamed to the leader, never stored. */
    private static final String ERROR_BODY = "{\"error\":{\"message\":\"rate limit exceeded\",\"type\":"
            + "\"rate_limit_error\"}}";

    private static final String TOOL_CALL_PAYLOAD = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\","
            + "\"content\":\"coalescer waiter contract probe\"}]}";

    // Deliberately a different user message than TOOL_CALL_PAYLOAD: the two cases
    // are separate tests, and distinct scopes keep them from sharing a cache key
    // through the context-wide L1 even if one of them ever becomes storable.
    private static final String ERROR_PAYLOAD = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\","
            + "\"content\":\"coalescer waiter error probe\"}]}";

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

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

    @Test
    @DisplayName("a waiter must not be answered with the leader's empty no-cache marker")
    void waiterFallsBackWhenTheLeaderHasNothingToReplay() throws Exception {
        // The mock records the request as soon as its body is aggregated and only
        // then waits, so a captured request proves the leader is in flight.
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").responseDelay(Duration.ofMillis(800)).body(TOOL_CALL_BODY).build());

        Reply leaderReply;
        Reply waiterReply;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> leader = pool.submit(() -> call(TOOL_CALL_PAYLOAD));
            awaitUpstreamCall();
            Future<Reply> waiter = pool.submit(() -> call(TOOL_CALL_PAYLOAD));
            leaderReply = leader.get(30, TimeUnit.SECONDS);
            waiterReply = waiter.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Sanity: the gateway is transparent for the leader.
        assertThat(leaderReply.status()).isEqualTo(200);
        assertThat(bodyOf(leaderReply)).isEqualTo(TOOL_CALL_BODY);

        // The leader's answer was never stored, so the only way the waiter can
        // receive it is its own upstream call — which is what the coalescer
        // contract promises when the shared work cannot be replayed.
        assertThat(mockProvider.getCapturedRequests())
                .as("upstream calls (waiter reply: HTTP %d, X-MiQroKey-Cache=%s, %d body bytes)", waiterReply.status(),
                        waiterReply.cacheHeader(), waiterReply.body().length)
                .hasSize(2);
        assertThat(bodyOf(waiterReply)).as("waiter body (\"coalesced\" must never mean \"empty\")")
                .isEqualTo(TOOL_CALL_BODY);
    }

    @Test
    @DisplayName("a waiter must not be answered with the leader's dropped error body")
    void waiterFallsBackWhenTheLeaderFailed() throws Exception {
        // Non-2xx: the gateway streams the error to the leader but refuses to store
        // it, so the leader's observed response is a status-plus-empty-body marker.
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(429)
                .contentType("application/json").responseDelay(Duration.ofMillis(800)).body(ERROR_BODY).build());

        Reply waiterReply;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> leader = pool.submit(() -> call(ERROR_PAYLOAD));
            awaitUpstreamCall();
            Future<Reply> waiter = pool.submit(() -> call(ERROR_PAYLOAD));
            leader.get(30, TimeUnit.SECONDS);
            waiterReply = waiter.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(mockProvider.getCapturedRequests())
                .as("upstream calls (waiter reply: HTTP %d, X-MiQroKey-Cache=%s, %d body bytes)", waiterReply.status(),
                        waiterReply.cacheHeader(), waiterReply.body().length)
                .hasSize(2);
        assertThat(bodyOf(waiterReply)).as("waiter body (the upstream error text must not be dropped)")
                .isEqualTo(ERROR_BODY);
    }

    private Reply call(String payload) {
        EntityExchangeResult<byte[]> result = webTestClient.post().uri("/v1/chat/completions")
                .header(CacheEligibility.CACHEABLE_HEADER, "1").bodyValue(payload).exchange().expectBody()
                .returnResult();
        byte[] body = result.getResponseBody();
        String cacheHeader = result.getResponseHeaders() == null
                ? null
                : result.getResponseHeaders().getFirst(SseReplayEngine.X_MIQROKEY_CACHE);
        return new Reply(result.getStatus().value(), cacheHeader, body == null ? new byte[0] : body);
    }

    private static String bodyOf(Reply reply) {
        return new String(reply.body(), StandardCharsets.UTF_8);
    }

    private static void awaitUpstreamCall() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (mockProvider.getCapturedRequests().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(mockProvider.getCapturedRequests()).as("leader reached the upstream").isNotEmpty();
    }

    private record Reply(int status, String cacheHeader, byte[] body) {
    }
}
