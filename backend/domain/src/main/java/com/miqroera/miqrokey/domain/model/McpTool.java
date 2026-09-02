package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One tool registered under an MCP service (P3.5, {@code mcp_tools} V21)
 * modeled after the Tencent AI gateway Tools management: the tool name is the
 * unique identifier AI agents invoke, and tools are enabled/disabled
 * individually.
 */
public record McpTool(UUID id, UUID tenantId, UUID mcpServiceId, String toolName, String description, String method,
        String path, String status, long version, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public McpTool {
        if (id == null || tenantId == null || mcpServiceId == null || toolName == null || toolName.isBlank()
                || path == null || path.isBlank()) {
            throw new IllegalArgumentException("id/tenantId/mcpServiceId/toolName/path are required");
        }
        if (method == null || !(method.equals("GET") || method.equals("POST") || method.equals("PUT")
                || method.equals("DELETE") || method.equals("PATCH"))) {
            throw new IllegalArgumentException("method must be GET, POST, PUT, DELETE or PATCH");
        }
        if (status == null || !(status.equals("ENABLED") || status.equals("DISABLED"))) {
            throw new IllegalArgumentException("status must be ENABLED or DISABLED");
        }
    }
}
