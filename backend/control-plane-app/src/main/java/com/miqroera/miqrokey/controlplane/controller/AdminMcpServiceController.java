package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpService;
import com.miqroera.miqrokey.domain.model.McpService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * MCP service management (P3.4, api-contract §5.16): registration, manual
 * online/offline switching and health check configuration. SYSTEM_ADMIN-only
 * via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-services")
public class AdminMcpServiceController {

    private final AdminMcpService mcpService;
    private final UserContext userContext;

    public AdminMcpServiceController(AdminMcpService mcpService, UserContext userContext) {
        this.mcpService = mcpService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<McpService> list() {
        return mcpService.list(userContext.getUser().tenantId());
    }

    @GetMapping("/{serviceId}")
    public McpService get(@PathVariable UUID serviceId) {
        return mcpService.get(userContext.getUser().tenantId(), serviceId);
    }

    @PostMapping
    public McpService create(@Valid @RequestBody CreateRequest body) {
        var user = userContext.getUser();
        return mcpService.create(user.tenantId(), user.id(), body.name().trim(), body.description(), body.endpoint(),
                body.transport(), body.checkIntervalSeconds(), body.checkTimeoutSeconds(), body.failThreshold(),
                body.recoverThreshold(), body.checkPath());
    }

    /** Manual online/offline switch (health checking never overrides it). */
    @PostMapping("/{serviceId}/status")
    public McpService setStatus(@PathVariable UUID serviceId, @RequestParam("status") String status) {
        return mcpService.setStatus(userContext.getUser().tenantId(), serviceId, status);
    }

    /** Updates the health check configuration. */
    @PostMapping("/{serviceId}/health-config")
    public McpService updateHealthConfig(@PathVariable UUID serviceId, @RequestBody HealthConfigRequest body) {
        return mcpService.updateHealthConfig(userContext.getUser().tenantId(), serviceId, body.checkIntervalSeconds(),
                body.checkTimeoutSeconds(), body.failThreshold(), body.recoverThreshold(), body.checkPath());
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @NotBlank @Size(max = 2048) String endpoint, String transport,
            @Min(5) @Max(3600) Integer checkIntervalSeconds, @Min(1) @Max(60) Integer checkTimeoutSeconds,
            @Min(1) @Max(20) Integer failThreshold, @Min(1) @Max(20) Integer recoverThreshold,
            @Size(max = 512) String checkPath) {
    }

    public record HealthConfigRequest(@Min(5) @Max(3600) Integer checkIntervalSeconds,
            @Min(1) @Max(60) Integer checkTimeoutSeconds, @Min(1) @Max(20) Integer failThreshold,
            @Min(1) @Max(20) Integer recoverThreshold, @Size(max = 512) String checkPath) {
    }
}
