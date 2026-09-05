package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;

import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_resilience_policy} (V30, F12/F13): one row per MCP
 * service; a missing row means both features are off.
 */
public interface McpResilienceRepository {

    Optional<McpResiliencePolicy> find(UUID tenantId, UUID mcpServiceId);

    /**
     * Insert or replace the service's policy row; the stored version increments on
     * every save. Returns the stored policy.
     */
    McpResiliencePolicy upsert(UUID tenantId, UUID mcpServiceId, McpResiliencePolicy policy, UUID updatedBy);
}
