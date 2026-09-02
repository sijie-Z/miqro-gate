package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_services} (V20, P3.4 MCP management).
 */
public interface McpServiceRepository {

    McpService insert(McpService service);

    Optional<McpService> findByIdAndTenantId(UUID id, UUID tenantId);

    List<McpService> findAllByTenantId(UUID tenantId);

    /** ONLINE services only — the health checker's probe list. */
    List<McpService> findAllOnlineByTenantId(UUID tenantId);

    /**
     * Replaces the full row (status switch or health update with optimistic lock).
     */
    McpService update(McpService service, long expectedVersion);
}
