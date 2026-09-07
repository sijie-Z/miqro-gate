package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.model.McpToolRevision;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRevisionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MCP tool definition versioning (F16, Tencent AI gateway raw 11 semantics):
 * edits publish immutable snapshots ({@code mcp_tool_revisions} V33) and move
 * the activation pointer; the active revision's spec is mirrored onto
 * {@code mcp_tools} so route-snapshot reads stay unchanged. History is never
 * pruned and rollback is an idempotent pointer move that does not mint a new
 * revision number.
 */
@Service
public class McpToolRevisionService {

    private final McpToolRepository toolRepository;
    private final McpToolRevisionRepository revisionRepository;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public McpToolRevisionService(McpToolRepository toolRepository, McpToolRevisionRepository revisionRepository,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.toolRepository = toolRepository;
        this.revisionRepository = revisionRepository;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    /**
     * Publishes a new definition revision: fields not supplied keep the current
     * active revision's values, so a call can be a full or partial edit. The next
     * revision becomes active and is mirrored onto the tool row.
     */
    @Transactional
    public McpToolRevision publish(UUID tenantId, UUID adminId, UUID toolId, String description, String method,
            String path) {
        McpTool tool = findTool(tenantId, toolId);
        McpToolRevision current = revisionRepository.findActive(tenantId, toolId).orElse(null);
        String baseDescription = current != null ? current.description() : tool.description();
        String baseMethod = current != null ? current.method() : tool.method();
        String basePath = current != null ? current.path() : tool.path();

        String mergedDescription = description == null ? baseDescription : description;
        String mergedMethod = normalizeMethod(method, baseMethod);
        String mergedPath = path == null || path.isBlank() ? basePath : path.trim();
        if (!mergedPath.startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_PATH_INVALID", "工具路径必须以 / 开头。");
        }

        long revision = revisionRepository.maxRevision(tenantId, toolId) + 1;
        Instant now = Instant.now();
        McpToolRevision created = new McpToolRevision(UUID.randomUUID(), tenantId, toolId, revision, mergedDescription,
                mergedMethod, mergedPath, adminId, now, now);
        try {
            // Clear the current activation pointer BEFORE inserting the active
            // revision: the partial unique index allows one active row per tool.
            revisionRepository.deactivateOthers(tenantId, toolId, revision);
            revisionRepository.insert(created);
            revisionRepository.mirrorToTool(tenantId, toolId, mergedDescription, mergedMethod, mergedPath);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_REVISION_CONFLICT", "并发发布冲突，请刷新后重试。");
        }
        routeRefreshPublisher.publishChanged();
        return created;
    }

    /** Newest-first history, capped at 50. */
    public List<McpToolRevision> list(UUID tenantId, UUID toolId, int limit) {
        findTool(tenantId, toolId);
        return revisionRepository.listByTool(tenantId, toolId, limit);
    }

    /**
     * Rollback / activation: moving the pointer to an older revision never mints a
     * new number and repeats are idempotent (activating the already-active revision
     * is a no-op success).
     */
    @Transactional
    public McpToolRevision activate(UUID tenantId, UUID toolId, long revision) {
        McpToolRevision target = revisionRepository.findByToolAndRevision(tenantId, toolId, revision)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TOOL_REVISION_NOT_FOUND", "工具修订不存在。"));
        if (target.activatedAt() == null) {
            revisionRepository.deactivateOthers(tenantId, toolId, revision);
            revisionRepository.activate(tenantId, toolId, revision, Instant.now());
            revisionRepository.mirrorToTool(tenantId, toolId, target.description(), target.method(), target.path());
            routeRefreshPublisher.publishChanged();
        }
        return revisionRepository.findByToolAndRevision(tenantId, toolId, revision).orElseThrow();
    }

    private McpTool findTool(UUID tenantId, UUID toolId) {
        return toolRepository.findByIdAndTenantId(toolId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND", "工具不存在。"));
    }

    private static String normalizeMethod(String method, String fallback) {
        if (method == null || method.isBlank()) {
            return fallback;
        }
        String normalized = method.trim().toUpperCase();
        if (!(normalized.equals("GET") || normalized.equals("POST") || normalized.equals("PUT")
                || normalized.equals("DELETE") || normalized.equals("PATCH"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_METHOD_INVALID",
                    "method 必须是 GET、POST、PUT、DELETE 或 PATCH。");
        }
        return normalized;
    }
}
