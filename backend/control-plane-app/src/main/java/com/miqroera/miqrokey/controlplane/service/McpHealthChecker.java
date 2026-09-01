package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Health checking for MCP services (P3.4) modeled after the Tencent AI gateway
 * health check: probes ONLINE services on their configured interval, counting
 * consecutive failures up to the fail threshold (HEALTHY -> UNHEALTHY) and
 * consecutive successes up to the recover threshold (UNHEALTHY -> HEALTHY). A
 * manual OFFLINE switch is never overridden by the checker — it probes only
 * ONLINE services.
 */
@Service
public class McpHealthChecker {

    private static final Logger LOG = LoggerFactory.getLogger(McpHealthChecker.class);

    /** The checker's own cycle; per-service intervals gate individual probes. */
    private static final Duration CYCLE = Duration.ofSeconds(15);

    private final McpServiceRepository repository;
    private final HttpClient http;

    public McpHealthChecker(McpServiceRepository repository) {
        this.repository = repository;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Scheduled(fixedDelayString = "${miqrokey.mcp.health-cycle-ms:15000}")
    public void checkAll() {
        List<McpService> online = repository.findAllOnlineByTenantId(SEED_TENANT_ID);
        Instant now = Instant.now();
        for (McpService service : online) {
            try {
                if (service.healthCheckedAt() != null
                        && service.healthCheckedAt().plusSeconds(service.checkIntervalSeconds()).isAfter(now)) {
                    continue; // not due yet
                }
                probe(service, now);
            } catch (Exception e) {
                LOG.warn("MCP health check failed for {}: {}", service.name(), sanitize(e));
            }
        }
    }

    private void probe(McpService service, Instant now) {
        boolean healthy = isHealthy(service);
        HealthState state = nextHealth(service, healthy);
        McpService updated = new McpService(service.id(), service.tenantId(), service.name(), service.description(),
                service.endpoint(), service.transport(), service.status(), state.healthStatus(), now, state.failures(),
                state.successes(), service.checkIntervalSeconds(), service.checkTimeoutSeconds(),
                service.failThreshold(), service.recoverThreshold(), service.checkPath(), service.version(),
                service.createdBy(), service.createdAt(), service.updatedAt());
        try {
            repository.update(updated, service.version());
        } catch (IllegalStateException e) {
            // Concurrent status switch won the optimistic lock; skip this cycle.
            LOG.debug("MCP health update skipped for {} (concurrent change)", service.name());
        }
    }

    /**
     * Pure transition: one probe outcome applied to the counters and health status.
     * HEALTHY after recoverThreshold consecutive successes, UNHEALTHY after
     * failThreshold consecutive failures.
     */
    static HealthState nextHealth(McpService service, boolean healthy) {
        int failures = healthy ? 0 : service.consecutiveFailures() + 1;
        int successes = healthy ? service.consecutiveSuccesses() + 1 : 0;
        String healthStatus = service.healthStatus();
        if (healthy && successes >= service.recoverThreshold()) {
            healthStatus = "HEALTHY";
        } else if (!healthy && failures >= service.failThreshold()) {
            healthStatus = "UNHEALTHY";
        }
        return new HealthState(healthStatus, failures, successes);
    }

    record HealthState(String healthStatus, int failures, int successes) {
    }

    /** GET {@code endpoint + checkPath}; 2xx counts as healthy. */
    boolean isHealthy(McpService service) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(service.endpoint() + service.checkPath()))
                    .timeout(Duration.ofSeconds(service.checkTimeoutSeconds())).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitize(Exception e) {
        String message = String.valueOf(e.getMessage());
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
}
