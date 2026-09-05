package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
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
 * configuration. Tool discovery is a follow-up.
 */
@Service
public class AdminMcpService {

    private final McpServiceRepository repository;
    private final AdminMcpRouteRuleService routeRules;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public AdminMcpService(McpServiceRepository repository, AdminMcpRouteRuleService routeRules,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.repository = repository;
        this.routeRules = routeRules;
        this.routeRefreshPublisher = routeRefreshPublisher;
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
            Integer recoverThreshold, String checkPath) {
        String normalizedEndpoint = validateEndpoint(endpoint);
        McpService service = new McpService(UUID.randomUUID(), tenantId, name, description, normalizedEndpoint,
                transport != null ? transport : "STREAMABLE_HTTP", "ONLINE", "UNKNOWN", null, 0, 0,
                checkIntervalSeconds != null ? checkIntervalSeconds : 30,
                checkTimeoutSeconds != null ? checkTimeoutSeconds : 5, failThreshold != null ? failThreshold : 3,
                recoverThreshold != null ? recoverThreshold : 1,
                checkPath != null && !checkPath.isBlank() ? checkPath : "/health", 0, adminId, Instant.now(),
                Instant.now());
        try {
            repository.insert(service);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_SERVICE_NAME_TAKEN", "MCP 服务名称已存在。");
        }
        // F11: every service owns an immutable default catch-all route.
        routeRules.createDefault(tenantId, service.id());
        routeRefreshPublisher.publishChanged();
        return service;
    }

    /** Manual online/offline switch; health checking never overrides it. */
    @Transactional
    public McpService setStatus(UUID tenantId, UUID serviceId, String status) {
        McpService service = find(tenantId, serviceId);
        if (!(status.equals("ONLINE") || status.equals("OFFLINE"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_STATUS_INVALID", "状态必须是 ONLINE 或 OFFLINE。");
        }
        if (service.status().equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_STATUS_UNCHANGED", "MCP 服务已处于该状态。");
        }
        McpService updated = repository.update(withStatus(service, status), service.version());
        routeRefreshPublisher.publishChanged();
        return updated;
    }

    /**
     * Updates the health check configuration (path/interval/timeout/thresholds).
     */
    @Transactional
    public McpService updateHealthConfig(UUID tenantId, UUID serviceId, Integer checkIntervalSeconds,
            Integer checkTimeoutSeconds, Integer failThreshold, Integer recoverThreshold, String checkPath) {
        McpService service = find(tenantId, serviceId);
        McpService updated = new McpService(service.id(), service.tenantId(), service.name(), service.description(),
                service.endpoint(), service.transport(), service.status(), service.healthStatus(),
                service.healthCheckedAt(), service.consecutiveFailures(), service.consecutiveSuccesses(),
                checkIntervalSeconds != null ? checkIntervalSeconds : service.checkIntervalSeconds(),
                checkTimeoutSeconds != null ? checkTimeoutSeconds : service.checkTimeoutSeconds(),
                failThreshold != null ? failThreshold : service.failThreshold(),
                recoverThreshold != null ? recoverThreshold : service.recoverThreshold(),
                checkPath != null && !checkPath.isBlank() ? checkPath : service.checkPath(), service.version(),
                service.createdBy(), service.createdAt(), service.updatedAt());
        return repository.update(updated, service.version());
    }

    private static McpService withStatus(McpService service, String status) {
        return new McpService(service.id(), service.tenantId(), service.name(), service.description(),
                service.endpoint(), service.transport(), status, service.healthStatus(), service.healthCheckedAt(),
                service.consecutiveFailures(), service.consecutiveSuccesses(), service.checkIntervalSeconds(),
                service.checkTimeoutSeconds(), service.failThreshold(), service.recoverThreshold(), service.checkPath(),
                service.version(), service.createdBy(), service.createdAt(), service.updatedAt());
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
