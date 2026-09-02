package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One consumer entry in an MCP access list (V25). {@code toolId} null = the
 * entry belongs to the server-level list; non-null = it refines that single
 * tool on top of the server decision.
 */
public record McpAccessGrant(UUID id, UUID tenantId, UUID serviceAccessId, UUID toolId, UUID consumerId,
        McpAclMode mode, UUID createdBy, long version, Instant createdAt, Instant updatedAt) {

    public McpAccessGrant {
        if (id == null || tenantId == null || serviceAccessId == null || consumerId == null || mode == null
                || createdBy == null) {
            throw new IllegalArgumentException("id/tenantId/serviceAccessId/consumerId/mode/createdBy are required");
        }
        if (mode == McpAclMode.NONE) {
            throw new IllegalArgumentException("grant mode must be ALLOW or DENY");
        }
    }
}
