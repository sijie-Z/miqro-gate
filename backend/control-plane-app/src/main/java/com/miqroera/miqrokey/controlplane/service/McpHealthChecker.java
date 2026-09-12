package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>
 * Probe shapes (#387, doc 135906): {@code HEALTH_PATH} (default) does the
 * legacy GET {@code endpoint + checkPath}; {@code JSONRPC_INITIALIZE} POSTs a
 * JSON-RPC 2.0 {@code initialize} envelope — the protocol-native liveness probe
 * for standard MCP servers without an HTTP health path. The JSON-RPC probe
 * counts 2xx with a JSON-RPC body (SSE-framed responses included) as healthy
 * and attaches the decrypted backend bearer for API_KEY services (fail-closed
 * when the credential is unavailable).
 * </p>
 */
@Service
public class McpHealthChecker {

    private static final Logger LOG = LoggerFactory.getLogger(McpHealthChecker.class);

    /** The checker's own cycle; per-service intervals gate individual probes. */
    private static final Duration CYCLE = Duration.ofSeconds(15);

    /** JSON-RPC 2.0 initialize envelope of the protocol-native probe (#387). */
    private static final String INITIALIZE_BODY = "{\"jsonrpc\":\"2.0\",\"id\":\"miqrokey-health\","
            + "\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"miqrokey-gateway\",\"version\":\"0.1.0\"}}}";

    private final McpServiceRepository repository;
    private final ObjectProvider<KeyEncryptionProvider> keyEncryptionProvider;
    private final HttpClient http;

    public McpHealthChecker(McpServiceRepository repository,
            ObjectProvider<KeyEncryptionProvider> keyEncryptionProvider) {
        this.repository = repository;
        this.keyEncryptionProvider = keyEncryptionProvider;
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
        // #415 (mirrors #361 for internal services): health telemetry is written
        // narrowly — no version check, no version bump — so the probe cycle can
        // never race an admin edit that runs under optimistic locking.
        repository.updateHealth(service.tenantId(), service.id(), state.healthStatus(), now, state.failures(),
                state.successes());
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

    boolean isHealthy(McpService service) {
        if (McpService.CHECK_MODE_JSONRPC.equals(service.checkMode())) {
            return jsonRpcHealthy(service);
        }
        return healthPathHealthy(service);
    }

    /** GET {@code endpoint + checkPath}; 2xx counts as healthy. */
    private boolean healthPathHealthy(McpService service) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(service.endpoint() + service.checkPath()))
                    .timeout(Duration.ofSeconds(service.checkTimeoutSeconds())).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * POST a JSON-RPC initialize envelope (#387); 2xx with a JSON-RPC body counts
     * as healthy — streamable HTTP servers may frame the response as
     * {@code text/event-stream}, so the check is a substring test over the body
     * rather than a JSON parse. API_KEY backends get the decrypted bearer; an
     * unavailable credential fails closed.
     */
    private boolean jsonRpcHealthy(McpService service) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(service.endpoint()))
                    .timeout(Duration.ofSeconds(service.checkTimeoutSeconds()))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY, StandardCharsets.UTF_8));
            if ("API_KEY".equals(service.backendAuthMode())) {
                String bearer = backendBearer(service);
                if (bearer == null) {
                    return false;
                }
                builder.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300 && response.body() != null
                    && response.body().contains("\"jsonrpc\"");
        } catch (Exception e) {
            return false;
        }
    }

    /** Decrypted backend bearer for API_KEY services; null when unavailable. */
    private String backendBearer(McpService service) {
        KeyEncryptionProvider crypto = keyEncryptionProvider == null ? null : keyEncryptionProvider.getIfAvailable();
        EncryptedSecret encrypted = repository == null
                ? null
                : repository.findBackendSecret(service.id(), service.tenantId()).orElse(null);
        if (crypto == null || encrypted == null) {
            return null;
        }
        try {
            byte[] secret = crypto.decrypt(encrypted, service.tenantId(), service.id());
            try {
                return new String(secret, StandardCharsets.UTF_8);
            } finally {
                com.miqroera.miqrokey.domain.crypto.impl.SecretWiping.clearArray(secret);
            }
        } catch (Exception e) {
            LOG.warn("MCP health probe credential unavailable for {}", service.name());
            return null;
        }
    }

    private static String sanitize(Exception e) {
        String message = String.valueOf(e.getMessage());
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
}
