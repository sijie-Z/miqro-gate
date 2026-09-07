package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpToolRevision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_tool_revisions} (V33, F16 tool definition versioning).
 * Revision rows are immutable except for the activation pointer; the caller
 * keeps {@code mcp_tools} mirrored to the active revision.
 */
public interface McpToolRevisionRepository {

    McpToolRevision insert(McpToolRevision revision);

    /** Highest revision number currently stored for the tool (0 when none). */
    long maxRevision(UUID tenantId, UUID toolId);

    Optional<McpToolRevision> findByToolAndRevision(UUID tenantId, UUID toolId, long revision);

    Optional<McpToolRevision> findActive(UUID tenantId, UUID toolId);

    /** Newest first, capped at {@code limit}. */
    List<McpToolRevision> listByTool(UUID tenantId, UUID toolId, int limit);

    /**
     * Clears the activation pointer of every revision except {@code keepRevision}.
     */
    int deactivateOthers(UUID tenantId, UUID toolId, long keepRevision);

    /**
     * Marks {@code revision} as active at {@code activatedAt}; returns rows
     * touched.
     */
    int activate(UUID tenantId, UUID toolId, long revision, Instant activatedAt);

    /**
     * Mirrors the revision spec onto {@code mcp_tools} for route-snapshot reads.
     */
    int mirrorToTool(UUID tenantId, UUID toolId, String description, String method, String path);
}
