package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminServiceService;
import com.miqroera.miqrokey.domain.model.InternalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Internal service registry (P3.2, api-contract §5.14): platform components and
 * MCP endpoints registered for gateway integration. SYSTEM_ADMIN-only via
 * RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/services")
public class AdminServiceController {

    private final AdminServiceService serviceService;
    private final UserContext userContext;

    public AdminServiceController(AdminServiceService serviceService, UserContext userContext) {
        this.serviceService = serviceService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<InternalService> list() {
        return serviceService.list(userContext.getUser().tenantId());
    }

    @GetMapping("/{serviceId}")
    public InternalService get(@PathVariable UUID serviceId) {
        return serviceService.get(userContext.getUser().tenantId(), serviceId);
    }

    @PostMapping
    public InternalService create(@Valid @RequestBody CreateRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return serviceService.create(user.tenantId(), user.id(), body.name().trim(), body.kind(), body.description(),
                body.baseUrl(), requestId(httpReq));
    }

    @PostMapping("/{serviceId}/disable")
    public InternalService disable(@PathVariable UUID serviceId, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return serviceService.disable(user.tenantId(), user.id(), serviceId, requestId(httpReq));
    }

    /** Re-enables a disabled service (#326; mirror of disable). */
    @PostMapping("/{serviceId}/enable")
    public InternalService enable(@PathVariable UUID serviceId, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return serviceService.enable(user.tenantId(), user.id(), serviceId, requestId(httpReq));
    }

    /** Health probe configuration (#326; partial update). */
    @PostMapping("/{serviceId}/health-config")
    public InternalService updateHealthConfig(@PathVariable UUID serviceId,
            @Valid @RequestBody HealthConfigRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return serviceService.updateHealthConfig(user.tenantId(), user.id(), serviceId, body.checkIntervalSeconds(),
                body.checkTimeoutSeconds(), body.failThreshold(), body.recoverThreshold(), body.checkPath(),
                requestId(httpReq));
    }

    public record HealthConfigRequest(@Min(5) @Max(3600) Integer checkIntervalSeconds,
            @Min(1) @Max(60) Integer checkTimeoutSeconds, @Min(1) @Max(20) Integer failThreshold,
            @Min(1) @Max(20) Integer recoverThreshold, @Size(max = 512) String checkPath) {
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "HTTP|MCP|OTHER", message = "kind must be HTTP, MCP or OTHER") String kind,
            @Size(max = 2000) String description, @NotBlank @Size(max = 2048) String baseUrl) {
    }
}
