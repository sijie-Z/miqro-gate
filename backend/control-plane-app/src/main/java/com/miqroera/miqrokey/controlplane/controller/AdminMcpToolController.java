package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminMcpToolService;
import com.miqroera.miqrokey.controlplane.service.McpToolRevisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.model.McpToolRevision;
import com.miqroera.miqrokey.controlplane.service.ToolOpenApiParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * MCP tools management (P3.5, api-contract §5.17): tools registered under an
 * MCP service with individual enable/disable, plus definition versioning (F16):
 * publishing an edit snapshots a new revision; rollback activates an older
 * revision. SYSTEM_ADMIN-only via RoleInterceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-services/{serviceId}/tools")
public class AdminMcpToolController {

    private final AdminMcpToolService toolService;
    private final McpToolRevisionService revisionService;
    private final UserContext userContext;

    public AdminMcpToolController(AdminMcpToolService toolService, McpToolRevisionService revisionService,
            UserContext userContext) {
        this.toolService = toolService;
        this.revisionService = revisionService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<McpTool> list(@PathVariable UUID serviceId) {
        return toolService.list(userContext.getUser().tenantId(), serviceId);
    }

    @PostMapping
    public McpTool create(@PathVariable UUID serviceId, @Valid @RequestBody CreateRequest body,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return toolService.create(user.tenantId(), user.id(), serviceId, body.toolName(), body.description(),
                body.method(), body.path(), requestId(httpReq));
    }

    /**
     * F17: batch-imports tools from an OpenAPI document (JSON). Tolerates unknown
     * vendor extensions; operations that cannot be derived or collide with existing
     * names are reported per item instead of failing the import.
     */
    @PostMapping("/import")
    public ImportResult importFromOpenApi(@PathVariable UUID serviceId, @RequestBody(required = false) JsonNode spec,
            HttpServletRequest httpReq) {
        var user = userContext.getUser();
        ToolOpenApiParser.Parsed parsed = ToolOpenApiParser.parse(spec);
        AdminMcpToolService.ImportReport report = toolService.createImported(user.tenantId(), user.id(), serviceId,
                parsed.tools(), requestId(httpReq));
        return new ImportResult(report.created(), report.skipped(), parsed.skipped());
    }

    /** Individual enable/disable of a tool. */
    @PostMapping("/{toolId}/status")
    public McpTool setStatus(@PathVariable UUID serviceId, @PathVariable UUID toolId,
            @RequestParam("status") String status, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return toolService.setStatus(user.tenantId(), user.id(), toolId, status, requestId(httpReq));
    }

    // ------------------------------------------------------------------
    // F16 definition versioning: immutable revision snapshots + activation.

    /** History of definition revisions, newest first (unpruned). */
    @GetMapping("/{toolId}/revisions")
    public List<McpToolRevision> revisions(@PathVariable UUID serviceId, @PathVariable UUID toolId,
            @RequestParam(defaultValue = "20") int limit) {
        return revisionService.list(userContext.getUser().tenantId(), toolId, limit);
    }

    /**
     * Publishes an edit as the next revision (partial edit: omitted fields keep the
     * active revision's values) and makes it the runtime definition.
     */
    @PostMapping("/{toolId}/revisions")
    public McpToolRevision publish(@PathVariable UUID serviceId, @PathVariable UUID toolId,
            @Valid @RequestBody PublishRequest body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return revisionService.publish(user.tenantId(), user.id(), toolId, body.description(), body.method(),
                body.path(), requestId(httpReq));
    }

    /**
     * Activates an existing revision (rollback included). Idempotent: activating
     * the already-active revision succeeds as a no-op; no new revision number is
     * minted and history is never pruned.
     */
    @PostMapping("/{toolId}/revisions/{revision}/activate")
    public McpToolRevision activate(@PathVariable UUID serviceId, @PathVariable UUID toolId,
            @PathVariable long revision, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        return revisionService.activate(user.tenantId(), user.id(), toolId, revision, requestId(httpReq));
    }

    public record CreateRequest(@NotBlank @Size(max = 128) String toolName, @Size(max = 2000) String description,
            @Pattern(regexp = "GET|POST|PUT|DELETE|PATCH", message = "method must be GET, POST, PUT, DELETE or PATCH") String method,
            @NotBlank @Size(max = 512) String path) {
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    public record PublishRequest(@Size(max = 2000) String description,
            @Pattern(regexp = "GET|POST|PUT|DELETE|PATCH", message = "method must be GET, POST, PUT, DELETE or PATCH") String method,
            @Size(max = 512) String path) {
    }

    public record ImportResult(java.util.List<McpTool> created, java.util.List<AdminMcpToolService.ImportSkip> skipped,
            java.util.List<ToolOpenApiParser.SkipNote> parseSkips) {
    }
}
