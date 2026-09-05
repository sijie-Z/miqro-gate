package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.McpMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract of the MCP invocation proxy (F01, Tencent doc 135906 shape):
 * consumer credential authentication against the snapshot digest, service
 * resolution, two-level access control ({@code McpAccessPolicy}: server mode
 * for every method, per-tool override and tool enablement for
 * {@code tools/call}), and verbatim passthrough (headers/status/body) of the
 * JSON-RPC envelope to the upstream.
 *
 * <p>
 * Fixtures live in {@link GatewayTestKeys}: every snapshot carries the open
 * ({@code NONE} mode) and gated ({@code ALLOW} mode) services under
 * {@code baseUrl}/mcp, plus the allowed / server-only / outsider consumers.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("MCP invocation proxy contract")
class McpProxyContractTest {

    private static final McpMockServer mockServer = new McpMockServer();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockServer::getBaseUrl);
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.close();
    }

    @AfterEach
    void resetMockServer() {
        mockServer.reset();
    }

    private static String errorType(byte[] body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            return root.path("error").path("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException("unparseable error body", e);
        }
    }

    /** A JSON-RPC envelope for the given method (optionally a tools/call name). */
    private static String envelope(String method, String toolName) {
        if (toolName == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"name\":\"" + toolName
                + "\",\"arguments\":{}}}";
    }

    private String bearer(GatewayTestKeys.ConsumerFixture consumer) {
        return "Bearer " + consumer.presentedKey();
    }

    // -------------------------------------------------------------------
    // 401/404/400 — credential, service and envelope failures
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("401/404/400 failure semantics")
    class FailureSemantics {

        @Test
        @DisplayName("should reject a request with no credential")
        void shouldRejectMissingCredential() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .headers(h -> h.set(HttpHeaders.AUTHORIZATION, ""))
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isUnauthorized().expectBody()
                    .returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("invalid_api_key");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should reject an unknown consumer key")
        void shouldRejectUnknownKey() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer mqk_api_drill_ghost_secret")
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isUnauthorized().expectBody()
                    .returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("invalid_api_key");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should accept a consumer key in the x-api-key header")
        void shouldAcceptXApiKeyHeader() {
            // The shared test client default Authorization header would win the
            // precedence check — drop it so only x-api-key is presented.
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .headers(h -> h.set(HttpHeaders.AUTHORIZATION, ""))
                    .header("x-api-key", GatewayTestKeys.MCP_OUTSIDER.presentedKey())
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isOk();

            assertThat(mockServer.capturedRequests()).hasSize(1);
            // The consumer credential is consumed at the gateway and never
            // forwarded upstream (same hygiene as the bearer path).
            assertThat(mockServer.capturedRequests().get(0).xApiKey()).isNull();
        }

        @Test
        @DisplayName("should reject an unknown MCP service name")
        void shouldRejectUnknownService() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", "no-such-service")
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_ALLOWED))
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isNotFound().expectBody()
                    .returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("mcp_service_not_found");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should reject a body that is not a JSON envelope")
        void shouldRejectMalformedEnvelope() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_ALLOWED)).bodyValue("this is not json")
                    .exchange().expectStatus().isBadRequest().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("invalid_jsonrpc");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Server mode NONE — open service
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("open service (server ACL NONE)")
    class OpenService {

        @Test
        @DisplayName("should let an unlisted consumer call tools/list")
        void shouldAllowUnlistedConsumerToolsList() {
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_OUTSIDER))
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isOk();

            assertThat(mockServer.capturedRequests()).hasSize(1);
            McpMockServer.Request upstream = mockServer.capturedRequests().get(0);
            assertThat(upstream.path()).isEqualTo("/mcp");
            assertThat(upstream.method()).isEqualTo("POST");
        }

        @Test
        @DisplayName("should forward an enabled tool call under NONE mode")
        void shouldForwardEnabledToolCall() {
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_OUTSIDER))
                    .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_ECHO)).exchange().expectStatus().isOk();

            assertThat(mockServer.capturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("should deny a disabled tool call even under NONE mode")
        void shouldDenyDisabledToolUnderNone() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_OUTSIDER))
                    .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_LEGACY)).exchange().expectStatus()
                    .isForbidden().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("mcp_tool_unavailable");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Server mode ALLOW — gated service
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("gated service (server ACL ALLOW)")
    class GatedService {

        @Test
        @DisplayName("should deny tools/list to a consumer outside the server list")
        void shouldDenyOutsiderServerList() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_OUTSIDER))
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isForbidden().expectBody()
                    .returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("mcp_access_denied");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should allow tools/list to a listed consumer")
        void shouldAllowListedConsumerToolsList() {
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_SERVER_ONLY))
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isOk();

            assertThat(mockServer.capturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("should inherit the server rule for a tool without an override")
        void shouldInheritServerRule() {
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_SERVER_ONLY))
                    .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED)).exchange().expectStatus().isOk();

            assertThat(mockServer.capturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("should allow a tools/call when the consumer is on the tool ALLOW list")
        void shouldAllowToolOverrideListedConsumer() {
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_ALLOWED))
                    .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).exchange().expectStatus()
                    .isOk();

            assertThat(mockServer.capturedRequests()).hasSize(1);
        }

        @Test
        @DisplayName("should deny tools/call when the tool override excludes a listed consumer")
        void shouldDenyToolOverrideExcludedConsumer() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_SERVER_ONLY))
                    .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_RESTRICTED)).exchange().expectStatus()
                    .isForbidden().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("mcp_access_denied");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should deny a disabled tool call to a fully allowed consumer")
        void shouldDenyDisabledTool() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_ALLOWED))
                    .bodyValue(envelope("tools/call", GatewayTestKeys.MCP_TOOL_QUIET)).exchange().expectStatus()
                    .isForbidden().expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("mcp_tool_unavailable");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should deny a tools/call naming a tool that is not registered")
        void shouldDenyUnknownTool() {
            byte[] body = webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_ALLOWED))
                    .bodyValue(envelope("tools/call", "not-a-real-tool")).exchange().expectStatus().isForbidden()
                    .expectBody().returnResult().getResponseBody();

            assertThat(errorType(body)).isEqualTo("mcp_tool_unavailable");
            assertThat(mockServer.capturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("should ignore the tool table for non tools/call methods")
        void shouldIgnoreToolTableForOtherMethods() {
            // initialize on a service that carries a disabled and a restricted
            // tool — neither may affect non tools/call methods.
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_SERVER_ONLY))
                    .bodyValue(envelope("initialize", null)).exchange().expectStatus().isOk();

            assertThat(mockServer.capturedRequests()).hasSize(1);
        }
    }

    // -------------------------------------------------------------------
    // Passthrough hygiene — headers, status and byte-identical bodies
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("verbatim passthrough")
    class Passthrough {

        @Test
        @DisplayName("should forward Session-Id and drop the caller credential upstream")
        void shouldForwardSessionIdNotCredential() {
            String requestBody = envelope("tools/call", GatewayTestKeys.MCP_TOOL_SHARED);
            webTestClient.post().uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_GATED_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_ALLOWED))
                    .header("Session-Id", "sess-contract-42").bodyValue(requestBody).exchange().expectStatus().isOk();

            McpMockServer.Request upstream = mockServer.capturedRequests().get(0);
            assertThat(upstream.sessionId()).isEqualTo("sess-contract-42");
            assertThat(upstream.authorization()).isNull();
            assertThat(upstream.xApiKey()).isNull();
            assertThat(new String(upstream.body(), StandardCharsets.UTF_8)).isEqualTo(requestBody);
        }

        @Test
        @DisplayName("should return the upstream response body byte-identical")
        void shouldEchoUpstreamBodyBytes() {
            String upstreamBody = "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"echo-tool\"}]},\"id\":1}";
            mockServer.setResponse(upstreamBody, 200);

            byte[] received = webTestClient.post()
                    .uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_OUTSIDER))
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isOk().expectBody()
                    .returnResult().getResponseBody();

            assertThat(new String(received, StandardCharsets.UTF_8)).isEqualTo(upstreamBody);
        }

        @Test
        @DisplayName("should copy the upstream status and body for an error response")
        void shouldCopyUpstreamErrorResponse() {
            String upstreamBody = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"upstream boom\"},\"id\":1}";
            mockServer.setResponse(upstreamBody, 503);

            byte[] received = webTestClient.post()
                    .uri("/mcpservers/{service}/mcp", GatewayTestKeys.MCP_OPEN_SERVICE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(GatewayTestKeys.MCP_OUTSIDER))
                    .bodyValue(envelope("tools/list", null)).exchange().expectStatus().isEqualTo(503).expectBody()
                    .returnResult().getResponseBody();

            assertThat(new String(received, StandardCharsets.UTF_8)).isEqualTo(upstreamBody);
        }
    }
}
