package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MCP tools management (P3.5, {@code mcp_tools} V21) modeled after the Tencent
 * AI gateway Tools management: tools are registered under an MCP service and
 * enabled/disabled individually; the tool name is the identifier AI agents
 * invoke.
 */
@Service
public class AdminMcpToolService {

    private final McpToolRepository toolRepository;
    private final McpServiceRepository serviceRepository;

    public AdminMcpToolService(McpToolRepository toolRepository, McpServiceRepository serviceRepository) {
        this.toolRepository = toolRepository;
        this.serviceRepository = serviceRepository;
    }

    public List<McpTool> list(UUID tenantId, UUID mcpServiceId) {
        requireService(tenantId, mcpServiceId);
        return toolRepository.findAllByService(tenantId, mcpServiceId);
    }

    @Transactional
    public McpTool create(UUID tenantId, UUID adminId, UUID mcpServiceId, String toolName, String description,
            String method, String path) {
        requireService(tenantId, mcpServiceId);
        String normalizedName = toolName.trim();
        if (!normalizedName.matches("[a-z][a-z0-9_]*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_NAME_INVALID", "工具名必须为小写字母开头的 snake_case。");
        }
        String normalizedPath = path.trim();
        if (!normalizedPath.startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_PATH_INVALID", "工具路径必须以 / 开头。");
        }
        McpTool tool = new McpTool(UUID.randomUUID(), tenantId, mcpServiceId, normalizedName, description,
                method != null ? method : "GET", normalizedPath, "ENABLED", 0, adminId, Instant.now(), Instant.now());
        try {
            toolRepository.insert(tool);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_NAME_TAKEN", "该服务下已存在同名工具。");
        }
        return tool;
    }

    /** Individual enable/disable of a tool (Tencent Tools 启停管理). */
    @Transactional
    public McpTool setStatus(UUID tenantId, UUID toolId, String status) {
        McpTool tool = find(tenantId, toolId);
        if (!(status.equals("ENABLED") || status.equals("DISABLED"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_STATUS_INVALID", "状态必须是 ENABLED 或 DISABLED。");
        }
        if (tool.status().equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_STATUS_UNCHANGED", "工具已处于该状态。");
        }
        return toolRepository.updateStatus(tenantId, toolId, status, tool.version());
    }

    private void requireService(UUID tenantId, UUID mcpServiceId) {
        serviceRepository.findByIdAndTenantId(mcpServiceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP 服务不存在。"));
    }

    private McpTool find(UUID tenantId, UUID toolId) {
        return toolRepository.findByIdAndTenantId(toolId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND", "工具不存在。"));
    }
}
