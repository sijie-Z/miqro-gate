package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable snapshot of an MCP tool definition (F16, {@code
 * mcp_tool_revisions} V33): publishing an edit appends the next revision and
 * activates it; rollback activates an older revision without creating a new
 * number. The active revision's spec is mirrored onto {@link McpTool} so the
 * route snapshot keeps reading the parent row.
 */
public record McpToolRevision(UUID id, UUID tenantId, UUID toolId, long revision, String description, String method,
        String path, UUID createdBy, Instant createdAt, Instant activatedAt) {

    public McpToolRevision {
        if (id == null || tenantId == null || toolId == null || revision < 1 || method == null || path == null
                || path.isBlank() || createdBy == null || createdAt == null) {
            throw new IllegalArgumentException("id/tenantId/toolId/revision/method/path/createdBy/createdAt required");
        }
        if (!(method.equals("GET") || method.equals("POST") || method.equals("PUT") || method.equals("DELETE")
                || method.equals("PATCH"))) {
            throw new IllegalArgumentException("method must be GET, POST, PUT, DELETE or PATCH");
        }
    }

    /** Whether this revision is the one the runtime currently uses. */
    public boolean active() {
        return activatedAt != null;
    }
}
