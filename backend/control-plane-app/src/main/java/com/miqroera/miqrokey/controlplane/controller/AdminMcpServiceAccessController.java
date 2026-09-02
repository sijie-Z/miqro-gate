package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.McpAccessView;
import com.miqroera.miqrokey.controlplane.dto.SetMcpAccessGrantsRequest;
import com.miqroera.miqrokey.controlplane.dto.SetMcpAccessModeRequest;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpAccessService;
import com.miqroera.miqrokey.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Two-level MCP access control (api-contract §5.21): server mode + lists at
 * server and per-tool level. SYSTEM_ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-services/{id}/access")
public class AdminMcpServiceAccessController {

    private final AdminMcpAccessService accessService;
    private final UserContext userContext;

    public AdminMcpServiceAccessController(AdminMcpAccessService accessService, UserContext userContext) {
        this.accessService = accessService;
        this.userContext = userContext;
    }

    @GetMapping
    public McpAccessView view(@PathVariable UUID id) {
        return accessService.view(user().tenantId(), id);
    }

    /** Server-level mode: NONE (open) / ALLOW / DENY. */
    @PutMapping("/mode")
    public McpAccessView setMode(@PathVariable UUID id, @Valid @RequestBody SetMcpAccessModeRequest body,
            HttpServletRequest httpReq) {
        return accessService.setMode(user().tenantId(), user().id(), id, body.mode(), requestId(httpReq));
    }

    /** Replaces the server list ({@code toolId} absent) or a tool's override. */
    @PutMapping("/grants")
    public McpAccessView replaceGrants(@PathVariable UUID id, @Valid @RequestBody SetMcpAccessGrantsRequest body,
            HttpServletRequest httpReq) {
        return accessService.replaceGrants(user().tenantId(), user().id(), id, body.toolId(), body.mode(),
                body.consumerIds(), requestId(httpReq));
    }

    /** Resets a scope: server list (back to open) or one tool's override. */
    @DeleteMapping("/grants")
    public McpAccessView clear(@PathVariable UUID id, @RequestParam(required = false) UUID toolId,
            HttpServletRequest httpReq) {
        return accessService.clear(user().tenantId(), user().id(), id, toolId, requestId(httpReq));
    }

    private User user() {
        return userContext.getUser();
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
