package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpToolRetryPolicy;

import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_tool_retry_policy} (V46, issue #360/I13): one row per
 * tool; a missing row means the service-level retry policy applies unchanged.
 */
public interface McpToolRetryRepository {

    Optional<McpToolRetryPolicy> find(UUID tenantId, UUID mcpToolId);

    /**
     * Insert or replace the tool's retry override; the stored version increments on
     * every save. Returns the stored policy.
     */
    McpToolRetryPolicy upsert(UUID tenantId, UUID mcpToolId, McpToolRetryPolicy policy, UUID updatedBy);
}
