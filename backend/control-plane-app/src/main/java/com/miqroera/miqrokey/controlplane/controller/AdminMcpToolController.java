package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpToolService;
import com.miqroera.miqrokey.domain.model.McpTool;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 * MCP tools management (P3.5, api-contract §5.17): tools registered under an
 * MCP service with individual enable/disable. SYSTEM_ADMIN-only via
 * RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-services/{serviceId}/tools")
public class AdminMcpToolController {

    private final AdminMcpToolService toolService;
    private final UserContext userContext;

    public AdminMcpToolController(AdminMcpToolService toolService, UserContext userContext) {
        this.toolService = toolService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<McpTool> list(@PathVariable UUID serviceId) {
        return toolService.list(userContext.getUser().tenantId(), serviceId);
    }

    @PostMapping
    public McpTool create(@PathVariable UUID serviceId, @Valid @RequestBody CreateRequest body) {
        var user = userContext.getUser();
        return toolService.create(user.tenantId(), user.id(), serviceId, body.toolName(), body.description(),
                body.method(), body.path());
    }

    /** Individual enable/disable of a tool. */
    @PostMapping("/{toolId}/status")
    public McpTool setStatus(@PathVariable UUID serviceId, @PathVariable UUID toolId,
            @RequestParam("status") String status) {
        return toolService.setStatus(userContext.getUser().tenantId(), toolId, status);
    }

    public record CreateRequest(@NotBlank @Size(max = 128) String toolName, @Size(max = 2000) String description,
            @Pattern(regexp = "GET|POST|PUT|DELETE|PATCH", message = "method must be GET, POST, PUT, DELETE or PATCH") String method,
            @NotBlank @Size(max = 512) String path) {
    }
}
