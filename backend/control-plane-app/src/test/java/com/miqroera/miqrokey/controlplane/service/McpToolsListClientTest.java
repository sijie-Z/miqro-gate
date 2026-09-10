package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upstream {@code tools/list} client (#344): request shape, response parsing
 * and the fail-closed error surface, against a loopback HTTP server.
 */
@DisplayName("McpToolsListClient")
class McpToolsListClientTest {

    private HttpServer server;
    private int port;
    private volatile int status = 200;
    private volatile String body = "{}";
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();

    private final McpToolsListClient client = new McpToolsListClient(new ObjectMapper());

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    @Test
    @DisplayName("parses names and descriptions; sends the fixed tools/list request with the bearer")
    void parsesToolsAndSendsBearer() {
        body = """
                {"jsonrpc":"2.0","id":1,"result":{"tools":[
                  {"name":"get_word","description":"每日单词"},
                  {"name":"post_json","description":""},
                  {"name":"no_desc"}
                ]}}
                """;

        List<McpToolsListClient.UpstreamTool> tools = client.fetchTools(url(), "Bearer sk-test");

        assertThat(tools).containsExactly(new McpToolsListClient.UpstreamTool("get_word", "每日单词"),
                new McpToolsListClient.UpstreamTool("post_json", null),
                new McpToolsListClient.UpstreamTool("no_desc", null));
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastBody.get()).isEqualTo(McpToolsListClient.REQUEST_BODY);
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    @DisplayName("VISITOR mode sends no Authorization header")
    void visitorSendsNoAuthorization() {
        body = "{\"result\":{\"tools\":[]}}";

        assertThat(client.fetchTools(url(), null)).isEmpty();
        assertThat(lastAuth.get()).isNull();
    }

    @Test
    @DisplayName("oversized names/descriptions are handled defensively")
    void longDescriptionIsTruncated() {
        String longDescription = "x".repeat(McpToolsListClient.MAX_DESCRIPTION_CHARS + 50);
        body = "{\"result\":{\"tools\":[{\"name\":\"big\",\"description\":\"" + longDescription + "\"}]}}";

        List<McpToolsListClient.UpstreamTool> tools = client.fetchTools(url(), null);

        assertThat(tools.get(0).description()).hasSize(McpToolsListClient.MAX_DESCRIPTION_CHARS);
    }

    @Test
    @DisplayName("non-2xx upstream is a sanitized 502")
    void non2xxIsSanitized() {
        status = 503;
        body = "upstream unavailable";

        assertThatThrownBy(() -> client.fetchTools(url(), null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("TOOLS_SYNC_UPSTREAM_FAILED");
            assertThat(e.getMessage()).contains("503").doesNotContain("127.0.0.1");
        });
    }

    @Test
    @DisplayName("a JSON-RPC error member surfaces the upstream message")
    void jsonRpcErrorSurfaces() {
        body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";

        assertThatThrownBy(() -> client.fetchTools(url(), null)).hasMessageContaining("method not found");
    }

    @Test
    @DisplayName("invalid JSON, missing tools array and oversized bodies fail closed")
    void malformedResponsesFailClosed() {
        body = "not json";
        assertThatThrownBy(() -> client.fetchTools(url(), null)).isInstanceOf(ApiException.class);

        body = "{\"result\":{\"content\":[]}}";
        assertThatThrownBy(() -> client.fetchTools(url(), null)).hasMessageContaining("result.tools");

        body = "x".repeat(McpToolsListClient.MAX_BODY_BYTES + 1);
        assertThatThrownBy(() -> client.fetchTools(url(), null)).hasMessageContaining("2MB");
    }

    @Test
    @DisplayName("more than the tool-count cap fails closed")
    void tooManyToolsFailClosed() throws Exception {
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i <= McpToolsListClient.MAX_TOOLS; i++) {
            tools.append(i == 0 ? "" : ",").append("{\"name\":\"tool_").append(i).append("\"}");
        }
        body = "{\"result\":{\"tools\":[" + tools + "]}}";

        assertThatThrownBy(() -> client.fetchTools(url(), null)).hasMessageContaining("1000");
    }
}
