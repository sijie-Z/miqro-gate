package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ExportTaskView;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.ApiException;
import com.miqroera.miqrokey.controlplane.service.ExportTaskService;
import com.miqroera.miqrokey.domain.usage.ExportFormat;
import com.miqroera.miqrokey.domain.usage.ExportTask;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Open export tasks (ADR-0015 read subset + ADR-0016 batch 2 write): recent
 * tasks and single-task metadata never carry the artifact bytes; creation is
 * delegated — the export is recorded against the issuing admin (option A) so
 * {@code created_by} keeps a real user while the audit trail stays on the
 * machine key. Download stays on the session surface.
 */
@RestController
@RequestMapping("/api/v1/admin-api/export-tasks")
public class OpenAdminExportsReadController {

    private final ExportTaskService exportTaskService;

    public OpenAdminExportsReadController(ExportTaskService exportTaskService) {
        this.exportTaskService = exportTaskService;
    }

    /**
     * Creates an export task on behalf of the issuing admin (ADR-0016 option A
     * delegation): the machine key authenticates, the task's {@code created_by} is
     * the key's issuing administrator. Response never contains bytes.
     */
    @PostMapping
    public ResponseEntity<ExportTask> create(HttpServletRequest request, @RequestParam ExportFormat format,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        UUID tenantId = tenantId(request);
        UUID issuer = issuerId(request, tenantId);
        ExportTask task = exportTaskService.create(tenantId, issuer, format, from, to);
        return ResponseEntity.accepted().body(task);
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

    private static UUID issuerId(HttpServletRequest request, UUID tenantId) {
        UUID issuer = (UUID) request.getAttribute(AdminApiKeyAuthFilter.ISSUER_ATTR);
        if (issuer == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EXECUTOR_UNKNOWN", "无法确定执行委托人：该机器密钥缺少发行管理员。");
        }
        return issuer;
    }
}
