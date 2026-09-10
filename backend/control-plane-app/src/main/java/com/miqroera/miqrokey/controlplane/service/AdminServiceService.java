package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.InternalService;
import com.miqroera.miqrokey.domain.repository.InternalServiceRepository;
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
 * Internal service registry (P3.2, {@code services} V18): platform components
 * and MCP endpoints registered for gateway integration. Base URLs must be https
 * without userinfo, mirroring the upstream target rules. Every mutation records
 * an audit event (SERVICE_CREATE/SERVICE_DISABLE).
 */
@Service
public class AdminServiceService {

    private final InternalServiceRepository serviceRepository;
    private final AuditService auditService;

    public AdminServiceService(InternalServiceRepository serviceRepository, AuditService auditService) {
        this.serviceRepository = serviceRepository;
        this.auditService = auditService;
    }

    public List<InternalService> list(UUID tenantId) {
        return serviceRepository.findAllByTenantId(tenantId);
    }

    public InternalService get(UUID tenantId, UUID serviceId) {
        return find(tenantId, serviceId);
    }

    @Transactional
    public InternalService create(UUID tenantId, UUID adminId, String name, String kind, String description,
            String baseUrl, String requestId) {
        String normalized = validateBaseUrl(baseUrl);
        String normalizedKind = kind == null || kind.isBlank() ? "HTTP" : kind;
        InternalService service = new InternalService(UUID.randomUUID(), tenantId, name.trim(), normalizedKind,
                description, normalized, "ACTIVE", 0, adminId, Instant.now(), Instant.now());
        try {
            serviceRepository.insert(service);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_NAME_TAKEN", "服务名称已存在。");
        }
        auditService.record(tenantId, adminId, "SERVICE_CREATE", "SERVICE", service.id(),
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name()), "kind", normalizedKind),
                requestId);
        return service;
    }

    @Transactional
    public InternalService disable(UUID tenantId, UUID adminId, UUID serviceId, String requestId) {
        InternalService service = find(tenantId, serviceId);
        if ("DISABLED".equals(service.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_ALREADY_DISABLED", "服务已禁用。");
        }
        InternalService updated = switchStatus(tenantId, serviceId, "DISABLED");
        auditService.record(tenantId, adminId, "SERVICE_DISABLE", "SERVICE", serviceId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name())), requestId);
        return updated;
    }

    /** Re-enables a disabled service (#326); mirror of disable with audit. */
    @Transactional
    public InternalService enable(UUID tenantId, UUID adminId, UUID serviceId, String requestId) {
        InternalService service = find(tenantId, serviceId);
        if ("ACTIVE".equals(service.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_ALREADY_ENABLED", "服务已启用。");
        }
        InternalService updated = switchStatus(tenantId, serviceId, "ACTIVE");
        auditService.record(tenantId, adminId, "SERVICE_ENABLE", "SERVICE", serviceId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name())), requestId);
        return updated;
    }

    /**
     * Status switch with a clean conflict signal: the write is compare-and-set on
     * the status column (#361), so losing a concurrent switch is a retriable 409,
     * never a 500.
     */
    private InternalService switchStatus(UUID tenantId, UUID serviceId, String status) {
        try {
            return serviceRepository.updateStatus(tenantId, serviceId, status);
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_STATE_CONFLICT", "并发状态变更，请刷新后重试。");
        }
    }

    /** Health probe configuration (partial update, mirror of the MCP endpoint). */
    @Transactional
    public InternalService updateHealthConfig(UUID tenantId, UUID adminId, UUID serviceId, Integer checkIntervalSeconds,
            Integer checkTimeoutSeconds, Integer failThreshold, Integer recoverThreshold, String checkPath,
            String requestId) {
        InternalService service = find(tenantId, serviceId);
        InternalService updated = new InternalService(service.id(), service.tenantId(), service.name(), service.kind(),
                service.description(), service.baseUrl(), service.status(), service.version(), service.createdBy(),
                service.createdAt(), service.updatedAt(), service.healthStatus(), service.healthCheckedAt(),
                service.consecutiveFailures(), service.consecutiveSuccesses(),
                checkIntervalSeconds != null ? checkIntervalSeconds : service.checkIntervalSeconds(),
                checkTimeoutSeconds != null ? checkTimeoutSeconds : service.checkTimeoutSeconds(),
                failThreshold != null ? failThreshold : service.failThreshold(),
                recoverThreshold != null ? recoverThreshold : service.recoverThreshold(),
                checkPath != null && !checkPath.isBlank() ? checkPath : service.checkPath());
        InternalService saved;
        try {
            saved = serviceRepository.update(updated, service.version());
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_STATE_CONFLICT", "并发状态变更，请刷新后重试。");
        }
        auditService.record(tenantId, adminId, "SERVICE_HEALTH_UPDATE", "SERVICE", serviceId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(service.name())), requestId);
        return saved;
    }

    /** https required, no userinfo, no query/fragment — mirror upstream rules. */
    static String validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BASE_URL_INVALID", "服务地址必填。");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BASE_URL_INVALID", "服务地址不是合法的 URL。");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BASE_URL_INVALID", "服务地址必须是 https URL。");
        }
        if (uri.getUserInfo() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BASE_URL_INVALID", "服务地址不能包含用户信息。");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BASE_URL_INVALID", "服务地址不能包含查询参数或片段。");
        }
        return uri.toString();
    }

    private InternalService find(UUID tenantId, UUID serviceId) {
        return serviceRepository.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "服务不存在。"));
    }
}
