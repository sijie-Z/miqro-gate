package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ExportTaskView;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.ExportTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Open export task query (ADR-0015, batch 1b read subset): recent tasks and a
 * single task's status, metadata only — never the artifact bytes (creation and
 * download stay on the session surface until the machine-executor semantics in
 * ADR-0016 settle).
 */
@RestController
@RequestMapping("/api/v1/admin-api/export-tasks")
public class OpenAdminExportsReadController {

    private final ExportTaskService exportTaskService;

    public OpenAdminExportsReadController(ExportTaskService exportTaskService) {
        this.exportTaskService = exportTaskService;
    }

    /** Recent tasks, newest first (metadata only, no artifact bytes). */
    @GetMapping
    public List<ExportTaskView> recent(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit) {
        return exportTaskService.recentMeta(tenantId(request), limit);
    }

    /** One task's metadata. */
    @GetMapping("/{taskId}")
    public ExportTaskView status(HttpServletRequest request, @PathVariable UUID taskId) {
        return exportTaskService.taskMeta(tenantId(request), taskId);
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }
}
