package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import com.miqroera.miqrokey.testing.McpMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12/F13 data-plane behavior over the real {@code /mcpservers} egress with the
 * in-memory fixture snapshot: retries before the first response byte (5xx,
 * connection-class failures), the non-idempotent tool gate, and the circuit
 * breaker fail-fast. Policies are installed per test by swapping the snapshot —
 * the same mechanism a route-snapshot refresh provides in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("MCP resilience data plane (F12 retry / F13 breaker)")
class McpResilienceIntegrationTest {

    private static final McpMockServer mockServer = new McpMockServer();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InMemoryRouteSnapshotProvider snapshotProvider;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockServer::getBaseUrl);
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.close();
    }

    @BeforeEach
    void resetState() {
        mockServer.reset();
        install(Map.of());
    }

    @AfterEach
    void resetStateAfter() {
        install(Map.of());
    }

    private void install(Map<String, McpResiliencePolicy> policies) {
        snapshotProvider.install(GatewayTestKeys.snapshotWithResilience(mockServer.getBaseUrl(), policies,
                GatewayTestKeys.DEFAULT_KEY, GatewayTestKeys.OTHER_KEY, GatewayTestKeys.GRANT_LIMITED_KEY,
                GatewayTestKeys.UPSTREAM_LIMITED_KEY, GatewayTestKeys.NO_UPSTREAM_KEY,
                GatewayTestKeys.UNKNOWN_PRODUCT_KEY));
    }

    private static String envelope(String method, String toolName) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"name\":\"" + toolName
                + "\",\"arguments\":{}}}";
    }

    private WebTestClient.ResponseSpec callGated(String consumer, String tool, String body) {
        return webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + consumer)).bodyValue(body).exchange();
    }

    private static String errorType(byte[] body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            return root.path("error").path("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException("unparseable error body", e);
        }
    }

    private static McpResiliencePolicy retryPolicy(boolean confirmed) {
        return new McpResiliencePolicy(true, 1, Set.of(McpResiliencePolicy.RetryCondition.SERVER_5XX), confirmed, false,
                10, 10, true, 50, Set.of(500), false, 3000, 80, 30, 3, 2, true, 0);
    }

    private static McpResiliencePolicy breakerPolicy() {
        return new McpResiliencePolicy(false, 1, Set.of(), false, true, 60, 2, true, 50, Set.of(500), false, 3000, 80,
                30, 3, 2, true, 0);
    }

    @Nested
    @DisplayName("F12 retry gate")
    class RetryGate {

        @Test
        @DisplayName("a 5xx followed by success is retried transparently (GET tool)")
        void retries5xxThenServesSuccess() {
            install(Map.of(GatewayTestKeys.MCP_GATED_SERVICE, retryPolicy(true))); // shared-tool is GET
            mockServer.queueResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1},\"id\":1}", 503);
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"result\":{\"ok\":true},\"id\":1}", 200);

            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_SHARED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED)).expectStatus().isOk();
            assertThat(mockServer.capturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("retry exhaustion returns the last upstream 5xx")
        void retryExhaustionReturns5xx() {
            install(Map.of(GatewayTestKeys.MCP_GATED_SERVICE, retryPolicy(true))); // one retry allowed
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1},\"id\":1}", 503);

            byte[] body = callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_SHARED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED)).expectStatus().isEqualTo(503).expectBody()
                    .returnResult().getResponseBody();
            assertThat(new String(body)).contains("\"code\":-1");
            assertThat(mockServer.capturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("POST/PUT/PATCH tool calls do not retry without the idempotency confirmation")
        void nonIdempotentToolNeedsConfirmation() {
            install(Map.of(GatewayTestKeys.MCP_GATED_SERVICE, retryPolicy(false))); // restricted-tool is POST
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1},\"id\":1}", 503);

            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_RESTRICTED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).expectStatus().isEqualTo(503);
            assertThat(mockServer.capturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("POST/PUT/PATCH tool calls retry once the confirmation is given")
        void nonIdempotentToolRetriesWhenConfirmed() {
            install(Map.of(GatewayTestKeys.MCP_GATED_SERVICE, retryPolicy(true)));
            mockServer.queueResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1},\"id\":1}", 503);
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"result\":{\"ok\":true},\"id\":1}", 200);

            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_RESTRICTED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).expectStatus().isOk();
            assertThat(mockServer.capturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("without a policy nothing changes: a single 5xx is served as-is")
        void disabledDefaultServesAsIs() {
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1},\"id\":1}", 503);
            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_SHARED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED)).expectStatus().isEqualTo(503);
            assertThat(mockServer.capturedRequests()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("F13 circuit breaker")
    class CircuitBreaker {

        @Test
        @DisplayName("repeated upstream failures open the breaker and fail fast with 503")
        void opensAndFastFails() {
            install(Map.of(GatewayTestKeys.MCP_GATED_SERVICE, breakerPolicy()));
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1},\"id\":1}", 500);

            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_SHARED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED)).expectStatus().isEqualTo(500);
            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_SHARED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED)).expectStatus().isEqualTo(500);

            byte[] body = callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_SHARED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED)).expectStatus().isEqualTo(503).expectBody()
                    .returnResult().getResponseBody();
            assertThat(errorType(body)).isEqualTo("circuit_open");
            // Only the two real upstream attempts happened.
            assertThat(mockServer.capturedRequests()).hasSize(2);
        }

        @Test
        @DisplayName("the breaker is per tool bucket: a healthy envelope method keeps working")
        void perToolBuckets() {
            install(Map.of(GatewayTestKeys.MCP_GATED_SERVICE, breakerPolicy()));
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1},\"id\":1}", 500);
            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_RESTRICTED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).expectStatus().isEqualTo(500);
            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_RESTRICTED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).expectStatus().isEqualTo(500);

            // A different bucket of the same service (tools/list) stays open.
            mockServer.setResponse("{\"jsonrpc\":\"2.0\",\"result\":{\"ok\":true},\"id\":1}", 200);
            callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").expectStatus().isOk();

            // The broken bucket still fast-fails.
            byte[] body = callGated(GatewayTestKeys.MCP_ALLOWED.presentedKey(), GatewayTestKeys.MCP_TOOL_RESTRICTED,
                    envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).expectStatus().isEqualTo(503)
                    .expectBody().returnResult().getResponseBody();
            assertThat(errorType(body)).isEqualTo("circuit_open");
            assertThat(mockServer.capturedRequests()).hasSize(3);
        }
    }
}
