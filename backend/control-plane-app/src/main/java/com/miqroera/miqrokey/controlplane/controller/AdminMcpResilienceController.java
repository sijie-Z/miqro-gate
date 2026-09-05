package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpResilienceService;
import com.miqroera.miqrokey.controlplane.service.AdminMcpResilienceService.RequestedPolicy;
import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F12/F13 resilience configuration of an MCP service (api-contract §5.25): GET
 * returns the effective policy (disabled defaults when no row exists), PUT
 * replaces it whole. SYSTEM_ADMIN-only via the RoleInterceptor deny-by-default.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-services/{serviceId}/resilience")
public class AdminMcpResilienceController {

    private final AdminMcpResilienceService service;
    private final UserContext userContext;

    public AdminMcpResilienceController(AdminMcpResilienceService service, UserContext userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    public McpResiliencePolicy get(@PathVariable UUID serviceId) {
        return service.view(userContext.getUser().tenantId(), serviceId);
    }

    @PutMapping
    public McpResiliencePolicy put(@PathVariable UUID serviceId, HttpServletRequest httpReq,
            @RequestBody(required = false) RequestedPolicy body) {
        var user = userContext.getUser();
        RequestedPolicy requested = body != null
                ? body
                : new RequestedPolicy(null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null);
        return service.configure(user.tenantId(), user.id(), serviceId, requested, requestId(httpReq));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header == null ? "" : header;
    }
}
