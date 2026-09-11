package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.repository.McpResilienceRepository;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MCP service management (P3.4, {@code mcp_services} V20) modeled after the
 * Tencent AI gateway MCP management: registration, manual online/offline
 * switching (health checking never overrides a manual offline) and health check
 * configuration. Tool discovery is a follow-up. Every mutation records an audit
 * event (MCP_SERVICE_CREATE/MCP_SERVICE_STATUS/MCP_SERVICE_HEALTH_UPDATE/
 * MCP_SERVICE_BACKEND_AUTH).
 */
@Service
public class AdminMcpService {

    private final McpServiceRepository repository;
    private final McpResilienceRepository resilienceRepository;
    private final AdminMcpRouteRuleService routeRules;
    private final RouteRefreshPublisher routeRefreshPublisher;
    private final AuditService auditService;
    private final KeyEncryptionProvider keyEncryptionProvider;

    public AdminMcpService(McpServiceRepository repository, McpResilienceRepository resilienceRepository,
            AdminMcpRouteRuleService routeRules, RouteRefreshPublisher routeRefreshPublisher, AuditService auditService,
            KeyEncryptionProvider keyEncryptionProvider) {
        this.repository = repository;
        this.resilienceRepository = resilienceRepository;
        this.routeRules = routeRules;
        this.routeRefreshPublisher = routeRefreshPublisher;
        this.auditService = auditService;
        this.keyEncryptionProvider = keyEncryptionProvider;
    }

    public List<McpService> list(UUID tenantId) {
        return repository.findAllByTenantId(tenantId);
    }

    public McpService get(UUID tenantId, UUID serviceId) {
        return find(tenantId, serviceId);
    }

    @Transactional
    public McpService create(UUID tenantId, UUID adminId, String name, String description, String endpoint,
            String transport, Integer checkIntervalSeconds, Integer checkTimeoutSeconds, Integer failThreshold,
            Integer recoverThreshold, String checkPath, Integer upstreamTimeoutMs, String requestId) {
        String normalizedEndpoint = validateEndpoint(endpoint);
        int timeout = upstreamTimeoutMs != null ? upstreamTimeoutMs : McpService.DEFAULT_UPSTREAM_TIMEOUT_MS;
        requireTimeoutRange(timeout);
        McpService service = new McpService(UUID.randomUUID(), tenantId, name.trim(), description, normalizedEndpoint,
                transport != null ? transport : "STREAMABLE_HTTP", "ONLINE", "UNKNOWN", null, 0, 0,
                checkIntervalSeconds != null ? checkIntervalSeconds : 30,
                checkTimeoutSeconds != null ? checkTimeoutSeconds : 5, failThreshold != null ? failThreshold : 3,
                recoverThreshold != null ? recoverThreshold : 1,
                checkPath != null && !checkPath.isBlank() ? checkPath : "/health", 0, adminId, Instant.now(),
                Instant.now(), "VISITOR", null, timeout);
        try {
            repository.insert(service);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_SERVICE_NAME_TAKEN", "MCP 服务名称已存在。");
        }
        // F11: every service owns an immutable default catch-all route.
        routeRules.createDefault(tenantId, service.id());
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "MCP_SERVICE_CREATE", "MCP_SERVICE", service.id(),
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name())), requestId);
        return service;
    }

    /** Manual online/offline switch; health checking never overrides it. */
    @Transactional
    public McpService setStatus(UUID tenantId, UUID adminId, UUID serviceId, String status, String requestId) {
        McpService service = find(tenantId, serviceId);
        if (!(status.equals("ONLINE") || status.equals("OFFLINE"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_STATUS_INVALID", "状态必须是 ONLINE 或 OFFLINE。");
        }
        if (service.status().equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_STATUS_UNCHANGED", "MCP 服务已处于该状态。");
        }
        McpService updated = repository.update(withStatus(service, status), service.version());
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "MCP_SERVICE_STATUS", "MCP_SERVICE", serviceId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name()), "status", status), requestId);
        return updated;
    }

    /**
     * Updates the health check configuration (path/interval/timeout/thresholds).
     */
    @Transactional
    public McpService updateHealthConfig(UUID tenantId, UUID adminId, UUID serviceId, Integer checkIntervalSeconds,
            Integer checkTimeoutSeconds, Integer failThreshold, Integer recoverThreshold, String checkPath,
            String requestId) {
        McpService service = find(tenantId, serviceId);
        McpService updated = new McpService(service.id(), service.tenantId(), service.name(), service.description(),
                service.endpoint(), service.transport(), service.status(), service.healthStatus(),
                service.healthCheckedAt(), service.consecutiveFailures(), service.consecutiveSuccesses(),
                checkIntervalSeconds != null ? checkIntervalSeconds : service.checkIntervalSeconds(),
                checkTimeoutSeconds != null ? checkTimeoutSeconds : service.checkTimeoutSeconds(),
                failThreshold != null ? failThreshold : service.failThreshold(),
                recoverThreshold != null ? recoverThreshold : service.recoverThreshold(),
                checkPath != null && !checkPath.isBlank() ? checkPath : service.checkPath(), service.version(),
                service.createdBy(), service.createdAt(), service.updatedAt(), service.backendAuthMode(),
                service.backendSecretUpdatedAt(), service.upstreamTimeoutMs());
        McpService saved = repository.update(updated, service.version());
        auditService.record(tenantId, adminId, "MCP_SERVICE_HEALTH_UPDATE", "MCP_SERVICE", serviceId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name())), requestId);
        return saved;
    }

    private static McpService withStatus(McpService service, String status) {
        return new McpService(service.id(), service.tenantId(), service.name(), service.description(),
                service.endpoint(), service.transport(), status, service.healthStatus(), service.healthCheckedAt(),
                service.consecutiveFailures(), service.consecutiveSuccesses(), service.checkIntervalSeconds(),
                service.checkTimeoutSeconds(), service.failThreshold(), service.recoverThreshold(), service.checkPath(),
                service.version(), service.createdBy(), service.createdAt(), service.updatedAt(),
                service.backendAuthMode(), service.backendSecretUpdatedAt(), service.upstreamTimeoutMs());
    }

    /**
     * Sets the data-plane upstream budget (I20, Tencent raw 03 "超时时间"):
     * 1000..600000 ms. Doc 134859: while a slow-call breaker trigger is enabled the
     * budget must stay above its threshold, so shrinking it at/below the active
     * {@code breakerSlowCallMs} is rejected (400).
     */
    @Transactional
    public McpService setUpstreamTimeout(UUID tenantId, UUID adminId, UUID serviceId, Integer upstreamTimeoutMs,
            String requestId) {
        McpService service = find(tenantId, serviceId);
        if (upstreamTimeoutMs == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_TIMEOUT_INVALID", "上游超时必填。");
        }
        requireTimeoutRange(upstreamTimeoutMs);
        resilienceRepository.find(tenantId, serviceId).ifPresent(policy -> {
            if (policy.breakerSlowEnabled() && policy.breakerSlowCallMs() >= upstreamTimeoutMs) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RESILIENCE_SLOW_EXCEEDS_TIMEOUT",
                        "上游超时必须高于已启用的慢调用阈值（" + policy.breakerSlowCallMs() + " ms）。");
            }
        });
        McpService updated = repository.updateUpstreamTimeout(serviceId, tenantId, upstreamTimeoutMs);
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "MCP_SERVICE_UPSTREAM_TIMEOUT", "MCP_SERVICE", serviceId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name()), "upstreamTimeoutMs",
                        String.valueOf(upstreamTimeoutMs)),
                requestId);
        return updated;
    }

    private static void requireTimeoutRange(int upstreamTimeoutMs) {
        if (upstreamTimeoutMs < McpService.MIN_UPSTREAM_TIMEOUT_MS
                || upstreamTimeoutMs > McpService.MAX_UPSTREAM_TIMEOUT_MS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_TIMEOUT_INVALID", "上游超时必须在 "
                    + McpService.MIN_UPSTREAM_TIMEOUT_MS + ".." + McpService.MAX_UPSTREAM_TIMEOUT_MS + " 毫秒。");
        }
    }

    /**
     * Sets the upstream backend authentication (#320, Tencent raw 03): VISITOR
     * clears any stored secret; API_KEY requires a non-blank secret which is
     * encrypted with the shared key ring (AAD bound to tenant + service id) and
     * never returned again. Summaries carry the mode only.
     */
    @Transactional
    public McpService setBackendAuth(UUID tenantId, UUID adminId, UUID serviceId, String mode, String secret,
            String requestId) {
        McpService service = find(tenantId, serviceId);
        if (!("VISITOR".equals(mode) || "API_KEY".equals(mode))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_BACKEND_AUTH_INVALID", "模式必须是 VISITOR 或 API_KEY。");
        }
        EncryptedSecret encrypted = null;
        if ("API_KEY".equals(mode)) {
            if (secret == null || secret.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_BACKEND_AUTH_INVALID", "API Key 模式必须提供密钥。");
            }
            if (secret.length() > 4096) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_BACKEND_AUTH_INVALID", "密钥长度超过上限（4096）。");
            }
            byte[] plaintext = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                encrypted = keyEncryptionProvider.encrypt(plaintext, tenantId, serviceId);
            } finally {
                com.miqroera.miqrokey.domain.crypto.impl.SecretWiping.clearArray(plaintext);
            }
        }
        McpService updated = repository.updateBackendAuth(serviceId, tenantId, mode, encrypted);
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "MCP_SERVICE_BACKEND_AUTH", "MCP_SERVICE", serviceId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name()), "mode", mode), requestId);
        return updated;
    }

    /** https required, no userinfo/query/fragment — mirror upstream rules. */
    static String validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_ENDPOINT_INVALID", "接入地址必填。");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_ENDPOINT_INVALID", "接入地址不是合法的 URL。");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_ENDPOINT_INVALID", "接入地址必须是 https URL。");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_ENDPOINT_INVALID", "接入地址不能包含用户信息/查询参数/片段。");
        }
        return uri.toString();
    }

    private McpService find(UUID tenantId, UUID serviceId) {
        return repository.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP 服务不存在。"));
    }
}
