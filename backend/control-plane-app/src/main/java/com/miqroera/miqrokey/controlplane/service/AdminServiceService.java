package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.InternalService;
import com.miqroera.miqrokey.domain.repository.InternalServiceRepository;
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
 * without userinfo, mirroring the upstream target rules.
 */
@Service
public class AdminServiceService {

    private final InternalServiceRepository serviceRepository;

    public AdminServiceService(InternalServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public List<InternalService> list(UUID tenantId) {
        return serviceRepository.findAllByTenantId(tenantId);
    }

    public InternalService get(UUID tenantId, UUID serviceId) {
        return find(tenantId, serviceId);
    }

    @Transactional
    public InternalService create(UUID tenantId, UUID adminId, String name, String kind, String description,
            String baseUrl) {
        String normalized = validateBaseUrl(baseUrl);
        String normalizedKind = kind == null || kind.isBlank() ? "HTTP" : kind;
        InternalService service = new InternalService(UUID.randomUUID(), tenantId, name, normalizedKind, description,
                normalized, "ACTIVE", 0, adminId, Instant.now(), Instant.now());
        try {
            serviceRepository.insert(service);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_NAME_TAKEN", "服务名称已存在。");
        }
        return service;
    }

    @Transactional
    public InternalService disable(UUID tenantId, UUID serviceId) {
        InternalService service = find(tenantId, serviceId);
        if ("DISABLED".equals(service.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_ALREADY_DISABLED", "服务已禁用。");
        }
        return serviceRepository.updateStatus(tenantId, serviceId, "DISABLED", service.version());
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
