package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpAccessGrant;
import com.miqroera.miqrokey.domain.model.McpServiceAccess;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_service_access} / {@code mcp_access_grants} (V25):
 * server-level ACL mode of an MCP service plus the consumer lists at server and
 * per-tool level.
 */
public interface McpAccessRepository {

    /** Insert or update the single (service) row; returns the stored row. */
    McpServiceAccess upsertService(McpServiceAccess access);

    Optional<McpServiceAccess> findService(UUID tenantId, UUID mcpServiceId);

    /** Every grant row of the service access (server list + all tool lists). */
    List<McpAccessGrant> findGrants(UUID tenantId, UUID serviceAccessId);

    /**
     * Replaces one scope of the list (server level when {@code toolId} is null,
     * otherwise that tool's override) with a fresh single-mode consumer set.
     */
    void replaceGrants(UUID tenantId, UUID serviceAccessId, UUID toolId, List<McpAccessGrant> grants);

    /** Removes one scope entirely (server list or a tool override). */
    void clearGrants(UUID tenantId, UUID serviceAccessId, UUID toolId);
}
