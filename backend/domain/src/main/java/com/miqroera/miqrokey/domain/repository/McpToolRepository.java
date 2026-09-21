package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_tools} (V21, P3.5 Tools management).
 */
public interface McpToolRepository {

    McpTool insert(McpTool tool);

    Optional<McpTool> findByIdAndTenantId(UUID id, UUID tenantId);

    List<McpTool> findAllByService(UUID tenantId, UUID mcpServiceId);

    /** Status update with optimistic version bump; returns the stored row. */
    McpTool updateStatus(UUID tenantId, UUID toolId, String status, long expectedVersion);
}
