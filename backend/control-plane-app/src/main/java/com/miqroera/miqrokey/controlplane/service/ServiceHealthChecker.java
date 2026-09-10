package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.InternalService;
import com.miqroera.miqrokey.domain.repository.InternalServiceRepository;
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
 * Health checking for internal registry services (issue #326, mirror of
 * {@link McpHealthChecker}): probes ACTIVE services on their own configured
 * interval — {@code GET baseUrl + checkPath}, 2xx counts healthy — and drives
 * UNKNOWN/HEALTHY/UNHEALTHY through the fail/recover thresholds. DISABLED rows
 * are never probed, so a manual stop is never overridden.
 */
@Service
public class ServiceHealthChecker {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceHealthChecker.class);

    private final InternalServiceRepository repository;
    private final HttpClient http;

    public ServiceHealthChecker(InternalServiceRepository repository) {
        this.repository = repository;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Scheduled(fixedDelayString = "${miqrokey.services.health-cycle-ms:15000}")
    public void checkAll() {
        List<InternalService> active = repository.findAllActiveByTenantId(SEED_TENANT_ID);
        Instant now = Instant.now();
        for (InternalService service : active) {
            try {
                if (service.healthCheckedAt() != null
                        && service.healthCheckedAt().plusSeconds(service.checkIntervalSeconds()).isAfter(now)) {
                    continue; // not due yet
                }
                probe(service, now);
            } catch (Exception e) {
                LOG.warn("Service health check failed for {}: {}", service.name(), sanitize(e));
            }
        }
    }

    private void probe(InternalService service, Instant now) {
        boolean healthy = isHealthy(service);
        HealthState state = nextHealth(service, healthy);
        InternalService updated = new InternalService(service.id(), service.tenantId(), service.name(), service.kind(),
                service.description(), service.baseUrl(), service.status(), service.version(), service.createdBy(),
                service.createdAt(), service.updatedAt(), state.healthStatus(), now, state.failures(),
                state.successes(), service.checkIntervalSeconds(), service.checkTimeoutSeconds(),
                service.failThreshold(), service.recoverThreshold(), service.checkPath());
        try {
            repository.update(updated, service.version());
        } catch (IllegalStateException e) {
            // Concurrent status/config change won the optimistic lock; skip.
            LOG.debug("Service health update skipped for {} (concurrent change)", service.name());
        }
    }

    /**
     * Pure transition: one probe outcome applied to the counters and health status;
     * HEALTHY after recoverThreshold consecutive successes, UNHEALTHY after
     * failThreshold consecutive failures.
     */
    static HealthState nextHealth(InternalService service, boolean healthy) {
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

    /** GET {@code baseUrl + checkPath}; 2xx counts as healthy. */
    boolean isHealthy(InternalService service) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(service.baseUrl() + service.checkPath()))
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
