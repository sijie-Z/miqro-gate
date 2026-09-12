package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpService;
import com.miqroera.miqrokey.controlplane.service.McpServiceTrafficService;
import com.miqroera.miqrokey.domain.model.McpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final McpServiceTrafficService trafficService;
    private final UserContext userContext;

    public AdminMcpServiceController(AdminMcpService mcpService, McpServiceTrafficService trafficService,
            UserContext userContext) {
        this.mcpService = mcpService;
        this.trafficService = trafficService;
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
    public McpService create(@Valid @RequestBody CreateRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return mcpService.create(user.tenantId(), user.id(), body.name().trim(), body.description(), body.endpoint(),
                body.transport(), body.checkIntervalSeconds(), body.checkTimeoutSeconds(), body.failThreshold(),
                body.recoverThreshold(), body.checkPath(), body.upstreamTimeoutMs(), requestId(httpReq));
    }

    /** Manual online/offline switch (health checking never overrides it). */
    @PostMapping("/{serviceId}/status")
    public McpService setStatus(@PathVariable UUID serviceId, @RequestParam("status") String status,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return mcpService.setStatus(user.tenantId(), user.id(), serviceId, status, requestId(httpReq));
    }

    /** Updates the health check configuration. */
    @PostMapping("/{serviceId}/health-config")
    public McpService updateHealthConfig(@PathVariable UUID serviceId, @RequestBody HealthConfigRequest body,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return mcpService.updateHealthConfig(user.tenantId(), user.id(), serviceId, body.checkIntervalSeconds(),
                body.checkTimeoutSeconds(), body.failThreshold(), body.recoverThreshold(), body.checkPath(),
                body.checkMode(), requestId(httpReq));
    }

    /**
     * Sets the upstream backend authentication (#320): {@code VISITOR} clears any
     * stored secret; {@code API_KEY} attaches
     * {@code Authorization: Bearer <secret>} upstream. The secret is write-only —
     * no read surface ever returns it.
     */
    @PutMapping("/{serviceId}/backend-auth")
    public McpService setBackendAuth(@PathVariable UUID serviceId, @Valid @RequestBody BackendAuthRequest body,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return mcpService.setBackendAuth(user.tenantId(), user.id(), serviceId, body.mode(), body.secret(),
                requestId(httpReq));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    /**
     * Sets the data-plane upstream budget (I20, Tencent raw 03 "超时时间"). Range and
     * the slow-call cross-check live in the service (typed 400s).
     */
    @PutMapping("/{serviceId}/upstream-timeout")
    public McpService setUpstreamTimeout(@PathVariable UUID serviceId, @RequestBody UpstreamTimeoutRequest body,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return mcpService.setUpstreamTimeout(user.tenantId(), user.id(), serviceId, body.upstreamTimeoutMs(),
                requestId(httpReq));
    }

    /**
     * Passive health (#397): per-service real-traffic window — the complement to
     * the active probe. Read-only; classification mirrors the consumer activity
     * endpoint (#338).
     */
    @GetMapping("/{serviceId}/traffic")
    public java.util.Map<String, Object> traffic(@PathVariable UUID serviceId,
            @RequestParam(defaultValue = "24") int hours) {
        return trafficService.traffic(userContext.getUser().tenantId(), serviceId, hours);
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @NotBlank @Size(max = 2048) String endpoint, String transport,
            @Min(5) @Max(3600) Integer checkIntervalSeconds, @Min(1) @Max(60) Integer checkTimeoutSeconds,
            @Min(1) @Max(20) Integer failThreshold, @Min(1) @Max(20) Integer recoverThreshold,
            @Size(max = 512) String checkPath, Integer upstreamTimeoutMs) {
    }

    public record UpstreamTimeoutRequest(Integer upstreamTimeoutMs) {
    }

    public record HealthConfigRequest(@Min(5) @Max(3600) Integer checkIntervalSeconds,
            @Min(1) @Max(60) Integer checkTimeoutSeconds, @Min(1) @Max(20) Integer failThreshold,
            @Min(1) @Max(20) Integer recoverThreshold, @Size(max = 512) String checkPath,
            /** #387: HEALTH_PATH (default) | JSONRPC_INITIALIZE. */
            String checkMode) {
    }

    public record BackendAuthRequest(@NotBlank String mode, @Size(max = 4096) String secret) {
    }
}
