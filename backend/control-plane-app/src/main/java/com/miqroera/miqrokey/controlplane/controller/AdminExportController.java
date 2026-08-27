package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.ExportTaskService;
import com.miqroera.miqrokey.domain.usage.ExportFormat;
import com.miqroera.miqrokey.domain.usage.ExportTask;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin raw-usage export tasks (G4.4, api-contract §5): create (202-style
 * async), status, download (gzip artifact with SHA-256 served until expiry) and
 * recent list. Access is SYSTEM_ADMIN-only via the deny-by-default
 * {@code /api/v1/admin/**} interceptor. Artifacts contain counts and metadata
 * only — never prompts, code, secrets or virtual-key plaintext.
 */
@RestController
@RequestMapping("/api/v1/admin/exports")
public class AdminExportController {

    private final ExportTaskService exportTaskService;
    private final UserContext userContext;

    public AdminExportController(ExportTaskService exportTaskService, UserContext userContext) {
        this.exportTaskService = exportTaskService;
        this.userContext = userContext;
    }

    /** Creates an export task; the artifact is produced asynchronously. */
    @PostMapping
    public ResponseEntity<ExportTask> create(@RequestParam ExportFormat format,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var user = userContext.getUser();
        ExportTask task = exportTaskService.create(user.tenantId(), user.id(), format, from, to);
        return ResponseEntity.accepted().body(task);
    }

    /** Task metadata (never the artifact bytes). */
    @GetMapping("/{taskId}")
    public ExportTask status(@PathVariable UUID taskId) {
        return exportTaskService.status(userContext.getUser().tenantId(), taskId);
    }

    /** Downloads the finished gzip artifact with its SHA-256 in the header. */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID taskId) {
        ExportTask task = exportTaskService.download(userContext.getUser().tenantId(), taskId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/gzip")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("miqrokey-usage-" + task.id() + "." + task.format().name().toLowerCase() + ".gz",
                                StandardCharsets.UTF_8)
                        .build().toString())
                .header("X-MiQroKey-SHA256", task.sha256())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(task.byteCount())).body(task.fileBytes());
    }

    /** Recent tasks for the admin UI. */
    @GetMapping
    public List<ExportTask> recent(@RequestParam(defaultValue = "20") int limit) {
        return exportTaskService.recent(userContext.getUser().tenantId(), limit);
    }
}
