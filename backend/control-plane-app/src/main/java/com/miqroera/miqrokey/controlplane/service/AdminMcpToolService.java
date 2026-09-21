package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.McpToolsListClient.UpstreamTool;

import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.model.McpToolRevision;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
import com.miqroera.miqrokey.domain.repository.McpToolRevisionRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MCP tools management (P3.5, {@code mcp_tools} V21) modeled after the Tencent
 * AI gateway Tools management: tools are registered under an MCP service and
 * enabled/disabled individually; the tool name is the identifier AI agents
 * invoke. Every mutation records an audit event
 * (MCP_TOOL_CREATE/MCP_TOOL_IMPORT/MCP_TOOL_STATUS).
 */
@Service
public class AdminMcpToolService {

    private final McpToolRepository toolRepository;
    private final McpServiceRepository serviceRepository;
    private final McpToolRevisionRepository revisionRepository;
    private final McpToolRevisionService revisionService;
    private final RouteRefreshPublisher routeRefreshPublisher;
    private final AuditService auditService;

    public AdminMcpToolService(McpToolRepository toolRepository, McpServiceRepository serviceRepository,
            McpToolRevisionRepository revisionRepository, McpToolRevisionService revisionService,
            RouteRefreshPublisher routeRefreshPublisher, AuditService auditService) {
        this.toolRepository = toolRepository;
        this.serviceRepository = serviceRepository;
        this.revisionRepository = revisionRepository;
        this.revisionService = revisionService;
        this.routeRefreshPublisher = routeRefreshPublisher;
        this.auditService = auditService;
    }

    public List<McpTool> list(UUID tenantId, UUID mcpServiceId) {
        requireService(tenantId, mcpServiceId);
        return toolRepository.findAllByService(tenantId, mcpServiceId);
    }

    @Transactional
    public McpTool create(UUID tenantId, UUID adminId, UUID mcpServiceId, String toolName, String description,
            String method, String path, String requestId) {
        requireService(tenantId, mcpServiceId);
        String normalizedName = toolName.trim();
        if (!normalizedName.matches("[a-z][a-z0-9_]*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_NAME_INVALID", "工具名必须为小写字母开头的 snake_case。");
        }
        String normalizedPath = path.trim();
        if (!normalizedPath.startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_PATH_INVALID", "工具路径必须以 / 开头。");
        }
        McpTool tool = new McpTool(UUID.randomUUID(), tenantId, mcpServiceId, normalizedName, description,
                method != null ? method : "GET", normalizedPath, "ENABLED", 0, adminId, Instant.now(), Instant.now());
        try {
            toolRepository.insert(tool);
            // Seed the immutable baseline revision (F16): every tool always has
            // revision 1 as its rollback target, active at creation.
            Instant now = Instant.now();
            revisionRepository.insert(new McpToolRevision(UUID.randomUUID(), tenantId, tool.id(), 1, tool.description(),
                    tool.method(), tool.path(), adminId, now, now));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_NAME_TAKEN", "该服务下已存在同名工具。");
        }
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "MCP_TOOL_CREATE", "MCP_TOOL", tool.id(),
                AuditSummaries.summary("name", normalizedName, "method", tool.method(), "path", tool.path()),
                requestId);
        return tool;
    }

    /**
     * F17 batch import: inserts parser-validated tool specs and seeds their
     * baseline revision, skipping per-item conflicts instead of failing the whole
     * request. A single route-refresh notification is published for the batch
     * (create publishes per call, so this path stays one signal).
     */
    @Transactional
    public ImportReport createImported(UUID tenantId, UUID adminId, UUID mcpServiceId,
            List<ToolOpenApiParser.ToolSpec> specs, String requestId) {
        requireService(tenantId, mcpServiceId);
        // Duplicate detection happens inside the transaction (before any write):
        // catching a duplicate AFTER the insert would mark the shared tx
        // rollback-only and fail the whole import on commit.
        Set<String> names = new java.util.HashSet<>();
        toolRepository.findAllByService(tenantId, mcpServiceId).forEach(tool -> names.add(tool.toolName()));
        List<McpTool> created = new java.util.ArrayList<>();
        List<ImportSkip> skipped = new java.util.ArrayList<>();
        for (ToolOpenApiParser.ToolSpec spec : specs) {
            String normalizedName = spec.toolName();
            String normalizedPath = spec.path().trim();
            if (!normalizedPath.startsWith("/")) {
                skipped.add(new ImportSkip(normalizedName, "路径必须以 / 开头"));
                continue;
            }
            if (!names.add(normalizedName)) {
                skipped.add(new ImportSkip(normalizedName, "该服务下已存在同名工具"));
                continue;
            }
            Instant now = Instant.now();
            McpTool tool = new McpTool(UUID.randomUUID(), tenantId, mcpServiceId, normalizedName, spec.description(),
                    spec.method(), normalizedPath, "ENABLED", 0, adminId, now, now);
            toolRepository.insert(tool);
            revisionRepository.insert(new McpToolRevision(UUID.randomUUID(), tenantId, tool.id(), 1, tool.description(),
                    tool.method(), tool.path(), adminId, now, now));
            created.add(tool);
        }
        if (!created.isEmpty()) {
            routeRefreshPublisher.publishChanged();
        }
        auditService.record(tenantId, adminId, "MCP_TOOL_IMPORT", "MCP_TOOL", mcpServiceId, AuditSummaries.summary(
                "service", mcpServiceId.toString(), "created", created.size(), "skipped", skipped.size()), requestId);
        return new ImportReport(created, skipped);
    }

    public record ImportReport(List<McpTool> created, List<ImportSkip> skipped) {
    }

    /**
     * Applies a tools/list sync plan (issue #344): registers added tools with their
     * baseline revision, refreshes changed descriptions through the next revision
     * (F16), and reports upstream-absent tools without touching them. Runs in one
     * transaction — the upstream call happened before this point, so any failure
     * here rolls the whole batch back without partial rows.
     */
    @Transactional
    public Map<String, Object> applySync(UUID tenantId, UUID adminId, UUID mcpServiceId, List<UpstreamTool> upstream,
            String requestId) {
        requireService(tenantId, mcpServiceId);
        List<McpTool> existing = toolRepository.findAllByService(tenantId, mcpServiceId);
        McpToolSyncService.Diff diff = McpToolSyncService.classify(existing, upstream);
        Instant now = Instant.now();
        for (UpstreamTool spec : diff.addedSpecs()) {
            McpTool tool = new McpTool(UUID.randomUUID(), tenantId, mcpServiceId, spec.name(), spec.description(),
                    McpToolSyncService.SYNCED_METHOD, McpToolSyncService.SYNCED_PATH, "ENABLED", 0, adminId, now, now);
            try {
                toolRepository.insert(tool);
            } catch (DuplicateKeyException e) {
                // Another writer raced the same tool name; fail the batch, nothing partial.
                throw new ApiException(HttpStatus.CONFLICT, "TOOLS_SYNC_CONFLICT", "并发同步冲突，请重试。");
            }
            revisionRepository.insert(new McpToolRevision(UUID.randomUUID(), tenantId, tool.id(), 1, tool.description(),
                    tool.method(), tool.path(), adminId, now, now));
        }
        Map<String, String> upstreamDescription = new HashMap<>();
        for (UpstreamTool spec : upstream) {
            upstreamDescription.put(spec.name(), spec.description());
        }
        for (McpTool tool : diff.updatedTools()) {
            revisionService.publishSyncDescription(tenantId, adminId, tool.id(),
                    upstreamDescription.get(tool.toolName()));
        }
        if (!diff.addedSpecs().isEmpty() || !diff.updatedTools().isEmpty()) {
            routeRefreshPublisher.publishChanged();
        }
        auditService.record(tenantId, adminId, "MCP_TOOLS_SYNCED", "MCP_SERVICE", mcpServiceId,
                AuditSummaries.summary("upstream", upstream.size(), "added", diff.addedSpecs().size(), "updated",
                        diff.updatedTools().size(), "absentUpstream", diff.absentUpstream().size(), "skipped",
                        diff.skipped().size()),
                requestId);
        return McpToolSyncService.report(false, upstream.size(), diff);
    }

    public record ImportSkip(String toolName, String reason) {
    }

    /** Individual enable/disable of a tool (Tencent Tools 启停管理). */
    @Transactional
    public McpTool setStatus(UUID tenantId, UUID adminId, UUID toolId, String status, String requestId) {
        McpTool tool = find(tenantId, toolId);
        if (!(status.equals("ENABLED") || status.equals("DISABLED"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_STATUS_INVALID", "状态必须是 ENABLED 或 DISABLED。");
        }
        if (tool.status().equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_STATUS_UNCHANGED", "工具已处于该状态。");
        }
        McpTool updated = toolRepository.updateStatus(tenantId, toolId, status, tool.version());
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "MCP_TOOL_STATUS", "MCP_TOOL", toolId,
                AuditSummaries.summary("name", tool.toolName(), "status", status), requestId);
        return updated;
    }

    private void requireService(UUID tenantId, UUID mcpServiceId) {
        serviceRepository.findByIdAndTenantId(mcpServiceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP 服务不存在。"));
    }

    private McpTool find(UUID tenantId, UUID toolId) {
        return toolRepository.findByIdAndTenantId(toolId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND", "工具不存在。"));
    }
}
