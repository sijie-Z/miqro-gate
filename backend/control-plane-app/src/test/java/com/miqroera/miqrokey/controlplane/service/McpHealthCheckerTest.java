package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.McpHealthChecker.HealthState;
import com.miqroera.miqrokey.domain.model.McpService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

        boolean healthy = new McpHealthChecker(null).isHealthy(service);

        assertThat(healthy).isTrue();
    }

    @Test
    @DisplayName("non-2xx and connection failures count as unhealthy")
    void unhealthyOutcomes() {
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        McpService service = service("http://127.0.0.1:" + port, 200, 3, 1);

        assertThat(new McpHealthChecker(null).isHealthy(service)).isFalse();

        // Connection refused (server stopped).
        server.stop(0);
        assertThat(new McpHealthChecker(null).isHealthy(service)).isFalse();
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
}
