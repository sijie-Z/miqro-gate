package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable snapshot of an MCP tool definition (F16, {@code
 * mcp_tool_revisions} V33): publishing an edit appends the next revision and
 * activates it; rollback activates an older revision without creating a new
 * number. The active revision's spec is mirrored onto {@link McpTool} so the
 * route snapshot keeps reading the parent row.
 */
public record McpToolRevision(UUID id, UUID tenantId, UUID toolId, long revision, String description, String method,
        String path, UUID createdBy, Instant createdAt, Instant activatedAt, List<String> changedFields) {

    /** Compatibility constructor: no computed diff attached (stored shape). */
    public McpToolRevision(UUID id, UUID tenantId, UUID toolId, long revision, String description, String method,
            String path, UUID createdBy, Instant createdAt, Instant activatedAt) {
        this(id, tenantId, toolId, revision, description, method, path, createdBy, createdAt, activatedAt, List.of());
    }

    public McpToolRevision {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
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

    /**
     * Field-level diff against the previous (older) revision (F16 history view,
     * issue #354): computed on read, never persisted. A null previous means this is
     * the baseline revision, which has nothing to diff against.
     */
    public List<String> changedFieldsVs(McpToolRevision previous) {
        if (previous == null) {
            return List.of();
        }
        List<String> changed = new ArrayList<>(3);
        if (!Objects.equals(description, previous.description)) {
            changed.add("description");
        }
        if (!method.equals(previous.method)) {
            changed.add("method");
        }
        if (!path.equals(previous.path)) {
            changed.add("path");
        }
        return changed;
    }
}
