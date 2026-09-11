package com.miqroera.miqrokey.gateway.mcplog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Webhook sink of the I19 access-log forwarding: one JSON array of
 * {@code aigw.mcp.*} metadata per batch, optional bearer token, and never-throw
 * delivery behavior.
 */
@DisplayName("MCP access log webhook forwarder")
class WebhookMcpAccessLogForwarderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> EXPECTED_KEYS = Set.of("aigw.mcp.event_id", "aigw.mcp.tenant_id",
            "aigw.mcp.service_id", "aigw.mcp.service", "aigw.mcp.consumer_id", "aigw.mcp.consumer",
            "aigw.mcp.rpc_method", "aigw.mcp.tool", "aigw.mcp.outcome", "aigw.mcp.http_status", "aigw.mcp.request_id",
            "aigw.mcp.session_id", "aigw.mcp.ttfb_ms", "aigw.mcp.occurred_at");

    private static McpAccessLogEntry entry(String requestId, String tool) {
        return new McpAccessLogEntry(UUID.randomUUID(), GatewayTestKeys.TENANT_ID, UUID.randomUUID(), "weather-mcp",
                UUID.randomUUID(), "drill", "tools/call", tool, McpAccessStatus.FORWARDED, 200, requestId,
                Instant.parse("2026-09-11T08:00:00Z"), "sess-1", 42L);
    }

    @Test
    @DisplayName("posts one JSON array of aigw.mcp.* metadata with the bearer token")
    void postsMetadataArray() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/logs", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            WebhookMcpAccessLogForwarder forwarder = new WebhookMcpAccessLogForwarder(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/logs", "t0ken", 5000, MAPPER);
            forwarder.forward(List.of(entry("req-1", "forecast"), entry("req-2", "alerts")));

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            JsonNode array = MAPPER.readTree(body.get());
            assertThat(array.isArray()).isTrue();
            assertThat(array).hasSize(2);
            JsonNode first = array.get(0);
            Set<String> keys = new java.util.TreeSet<>();
            first.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).isEqualTo(new java.util.TreeSet<>(EXPECTED_KEYS));
            assertThat(first.get("aigw.mcp.tool").asText()).isEqualTo("forecast");
            assertThat(first.get("aigw.mcp.request_id").asText()).isEqualTo("req-1");
            assertThat(first.get("aigw.mcp.http_status").asInt()).isEqualTo(200);
            assertThat(first.get("aigw.mcp.occurred_at").asText()).isEqualTo("2026-09-11T08:00:00Z");
            assertThat(authorization.get()).isEqualTo("Bearer t0ken");
            assertThat(forwarder.failureCount()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("non-2xx and unreachable endpoints degrade to a count, never a throw")
    void deliveryFailuresNeverThrow() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/logs", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            WebhookMcpAccessLogForwarder forwarder = new WebhookMcpAccessLogForwarder(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/logs", "", 2000, MAPPER);
            forwarder.forward(List.of(entry("req-1", "forecast")));
            assertThat(forwarder.failureCount()).isEqualTo(1);
        } finally {
            server.stop(0);
        }

        WebhookMcpAccessLogForwarder unreachable = new WebhookMcpAccessLogForwarder("http://127.0.0.1:1/logs", "", 1000,
                MAPPER);
        unreachable.forward(List.of(entry("req-1", "forecast")));
        assertThat(unreachable.failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalid configuration is rejected")
    void guards() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookMcpAccessLogForwarder("  ", null, 1000, MAPPER));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookMcpAccessLogForwarder("http://example.invalid", null, 0, MAPPER));
    }
}
