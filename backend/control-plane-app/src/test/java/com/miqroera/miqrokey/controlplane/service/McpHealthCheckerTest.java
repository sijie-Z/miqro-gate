package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.McpHealthChecker.HealthState;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP health checking (P3.4): probe outcome mapping and the fail/recover
 * threshold state machine, against a real loopback HTTP server.
 */
@DisplayName("McpHealthChecker")
class McpHealthCheckerTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("2xx probe outcome marks the service healthy")
    void healthyOutcome() {
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        McpService service = service("http://127.0.0.1:" + port, 200, 3, 1);

        boolean healthy = new McpHealthChecker(null, null).isHealthy(service);

        assertThat(healthy).isTrue();
    }

    @Test
    @DisplayName("JSON-RPC initialize probe: 2xx with a jsonrpc body is healthy (SSE frames included)")
    void jsonRpcProbeOutcomes() {
        AtomicReference<String> lastAccept = new AtomicReference<>();
        server.createContext("/mcp", exchange -> {
            lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\"},\"id\":\"miqrokey-health\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        McpService service = jsonRpcService("http://127.0.0.1:" + port + "/mcp", "VISITOR");
        assertThat(new McpHealthChecker(null, null).isHealthy(service)).isTrue();
        assertThat(lastAccept.get()).contains("text/event-stream");

        // SSE-framed body still carries the JSON-RPC payload.
        server.removeContext("/mcp");
        server.createContext("/mcp", exchange -> {
            byte[] body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"result\":{},\"id\":1}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        assertThat(new McpHealthChecker(null, null).isHealthy(service)).isTrue();

        // 2xx without a JSON-RPC body, non-2xx and connection failures are unhealthy.
        server.removeContext("/mcp");
        server.createContext("/mcp", exchange -> {
            byte[] body = "not json-rpc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        assertThat(new McpHealthChecker(null, null).isHealthy(service)).isFalse();

        server.removeContext("/mcp");
        server.createContext("/mcp", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        assertThat(new McpHealthChecker(null, null).isHealthy(service)).isFalse();

        server.stop(0);
        assertThat(new McpHealthChecker(null, null).isHealthy(service)).isFalse();
    }

    @Test
    @DisplayName("JSON-RPC probe attaches the decrypted bearer for API_KEY backends")
    void jsonRpcProbeAttachesBackendBearer() {
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/mcp", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        McpService service = jsonRpcService("http://127.0.0.1:" + port + "/mcp", "API_KEY");

        McpServiceRepository repository = mock(McpServiceRepository.class);
        when(repository.findBackendSecret(service.id(), service.tenantId()))
                .thenReturn(Optional.of(new EncryptedSecret(new byte[]{1}, new byte[]{2}, "v1")));
        KeyEncryptionProvider crypto = mock(KeyEncryptionProvider.class);
        when(crypto.decrypt(any(), any(), any())).thenReturn("sk-up-1".getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        ObjectProvider<KeyEncryptionProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(crypto);

        assertThat(new McpHealthChecker(repository, provider).isHealthy(service)).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer sk-up-1");
    }

    @Test
    @DisplayName("JSON-RPC probe fails closed for API_KEY backends without a usable credential")
    void jsonRpcProbeFailsClosedWithoutCredential() {
        server.createContext("/mcp", exchange -> {
            byte[] body = "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        McpService service = jsonRpcService("http://127.0.0.1:" + port + "/mcp", "API_KEY");

        McpServiceRepository repository = mock(McpServiceRepository.class);
        when(repository.findBackendSecret(service.id(), service.tenantId())).thenReturn(Optional.empty());

        // The upstream would answer 200, but the probe must refuse to call it
        // without the credential the data plane would present.
        assertThat(new McpHealthChecker(repository, null).isHealthy(service)).isFalse();
    }

    @Test
    @DisplayName("non-2xx and connection failures count as unhealthy")
    void unhealthyOutcomes() {
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        McpService service = service("http://127.0.0.1:" + port, 200, 3, 1);

        assertThat(new McpHealthChecker(null, null).isHealthy(service)).isFalse();

        // Connection refused (server stopped).
        server.stop(0);
        assertThat(new McpHealthChecker(null, null).isHealthy(service)).isFalse();
    }

    @Test
    @DisplayName("failures accumulate to the threshold then flip to UNHEALTHY")
    void failThresholdFlips() {
        McpService service = service("http://example.invalid", 200, 3, 1);

        HealthState first = McpHealthChecker.nextHealth(service, false);
        assertThat(first.healthStatus()).isEqualTo("UNKNOWN");
        assertThat(first.failures()).isEqualTo(1);

        McpService afterFirst = withCounters(service, first);
        HealthState second = McpHealthChecker.nextHealth(afterFirst, false);
        assertThat(second.healthStatus()).isEqualTo("UNKNOWN");
        assertThat(second.failures()).isEqualTo(2);

        McpService afterSecond = withCounters(service, second);
        HealthState third = McpHealthChecker.nextHealth(afterSecond, false);
        assertThat(third.healthStatus()).isEqualTo("UNHEALTHY");
        assertThat(third.failures()).isEqualTo(3);
    }

    @Test
    @DisplayName("recoverThreshold successes flip UNHEALTHY back to HEALTHY")
    void recoverThresholdFlips() {
        McpService unhealthy = new McpService(service("http://example.invalid", 200, 3, 1).id(),
                service("http://example.invalid", 200, 3, 1).tenantId(), "mcp", null, "http://example.invalid",
                "STREAMABLE_HTTP", "ONLINE", "UNHEALTHY", null, 3, 0, 30, 5, 3, 1, "/health", 0, UUID.randomUUID(),
                Instant.now(), Instant.now());

        HealthState recovered = McpHealthChecker.nextHealth(unhealthy, true);

        assertThat(recovered.healthStatus()).isEqualTo("HEALTHY");
        assertThat(recovered.successes()).isEqualTo(1);
        assertThat(recovered.failures()).isZero();
    }

    private static McpService withCounters(McpService service, HealthState state) {
        return new McpService(service.id(), service.tenantId(), service.name(), service.description(),
                service.endpoint(), service.transport(), service.status(), state.healthStatus(),
                service.healthCheckedAt(), state.failures(), state.successes(), service.checkIntervalSeconds(),
                service.checkTimeoutSeconds(), service.failThreshold(), service.recoverThreshold(), service.checkPath(),
                service.version(), service.createdBy(), service.createdAt(), service.updatedAt());
    }

    private static McpService service(String endpoint, int timeout, int failThreshold, int recoverThreshold) {
        return new McpService(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"), "mcp-test",
                null, endpoint, "STREAMABLE_HTTP", "ONLINE", "UNKNOWN", null, 0, 0, 30, timeout, failThreshold,
                recoverThreshold, "/health", 0, UUID.randomUUID(), Instant.now(), Instant.now());
    }

    private static McpService jsonRpcService(String endpoint, String backendAuthMode) {
        return new McpService(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"), "mcp-jsonrpc",
                null, endpoint, "STREAMABLE_HTTP", "ONLINE", "UNKNOWN", null, 0, 0, 30, 5, 3, 1, "/health", 0,
                UUID.randomUUID(), Instant.now(), Instant.now(), backendAuthMode,
                "API_KEY".equals(backendAuthMode) ? Instant.now() : null, 60000, McpService.CHECK_MODE_JSONRPC);
    }
}
