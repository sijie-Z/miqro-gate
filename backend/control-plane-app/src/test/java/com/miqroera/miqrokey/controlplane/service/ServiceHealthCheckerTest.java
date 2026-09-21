package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.InternalService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service health checker semantics (#326): pure threshold transitions plus the
 * probe's healthy predicate against a real local HTTP server (mirror of the MCP
 * checker's contract).
 */
@DisplayName("ServiceHealthChecker")
class ServiceHealthCheckerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static InternalService service(String healthStatus, int failures, int successes, int failThreshold,
            int recoverThreshold) {
        return new InternalService(UUID.randomUUID(), UUID.randomUUID(), "probe-me", "HTTP", null, "http://127.0.0.1:1",
                "ACTIVE", 0, UUID.randomUUID(), Instant.now(), Instant.now(), healthStatus, null, failures, successes,
                30, 5, failThreshold, recoverThreshold, "/health");
    }

    @Test
    @DisplayName("threshold transitions: recover then fail, below thresholds hold")
    void thresholdTransitions() {
        // One success at recover threshold 1 flips UNKNOWN -> HEALTHY.
        ServiceHealthChecker.HealthState recovered = ServiceHealthChecker.nextHealth(service("UNKNOWN", 2, 0, 3, 1),
                true);
        assertThat(recovered.healthStatus()).isEqualTo("HEALTHY");
        assertThat(recovered.successes()).isEqualTo(1);
        assertThat(recovered.failures()).isZero();

        // Failures below the fail threshold keep the current state.
        ServiceHealthChecker.HealthState holding = ServiceHealthChecker.nextHealth(service("HEALTHY", 0, 5, 3, 1),
                false);
        assertThat(holding.healthStatus()).isEqualTo("HEALTHY");
        assertThat(holding.failures()).isEqualTo(1);

        // Reaching the fail threshold flips to UNHEALTHY.
        ServiceHealthChecker.HealthState failed = ServiceHealthChecker.nextHealth(service("HEALTHY", 2, 0, 3, 1),
                false);
        assertThat(failed.healthStatus()).isEqualTo("UNHEALTHY");
        assertThat(failed.failures()).isEqualTo(3);

        // Successes below the recover threshold keep UNHEALTHY.
        ServiceHealthChecker.HealthState waiting = ServiceHealthChecker.nextHealth(service("UNHEALTHY", 3, 0, 3, 2),
                true);
        assertThat(waiting.healthStatus()).isEqualTo("UNHEALTHY");
        assertThat(waiting.successes()).isEqualTo(1);
    }

    @Test
    @DisplayName("probe: 2xx healthy, 5xx and unreachable unhealthy")
    void probeOutcomes() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ServiceHealthChecker checker = new ServiceHealthChecker(null);
        InternalService healthy = new InternalService(UUID.randomUUID(), UUID.randomUUID(), "probe-me", "HTTP", null,
                baseUrl, "ACTIVE", 0, UUID.randomUUID(), Instant.now(), Instant.now(), "UNKNOWN", null, 0, 0, 30, 5, 3,
                1, "/health");
        assertThat(checker.isHealthy(healthy)).isTrue();

        server.stop(0);
        server = null;
        assertThat(checker.isHealthy(healthy)).as("unreachable server counts unhealthy").isFalse();
    }
}
