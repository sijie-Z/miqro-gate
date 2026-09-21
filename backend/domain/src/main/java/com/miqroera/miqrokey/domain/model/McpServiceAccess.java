package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-level access plan of one MCP service (V25): the ACL mode plus the
 * consuming API consumers in its list. A missing row means NONE (open).
 */
public record McpServiceAccess(UUID id, UUID tenantId, UUID mcpServiceId, McpAclMode mode, UUID createdBy, long version,
        Instant createdAt, Instant updatedAt) {

    public McpServiceAccess {
        if (id == null || tenantId == null || mcpServiceId == null || mode == null || createdBy == null) {
            throw new IllegalArgumentException("id/tenantId/mcpServiceId/mode/createdBy are required");
        }
    }
}
