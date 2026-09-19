package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AdminRetentionLogView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminRetentionLogService;
import com.miqroera.miqrokey.domain.service.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin retention log (ADR-0014 §8, api-contract §8): paged decrypted view and
 * CSV export over {@code retention_log}. SYSTEM_ADMIN-only; every read is
 * itself audited ({@code RETENTION_LOG_VIEW} / {@code RETENTION_LOG_EXPORT}).
 */
@RestController
@RequestMapping("/api/v1/admin/retention-logs")
public class AdminRetentionLogController {

    private final AdminRetentionLogService retentionLogs;
    private final UserContext userContext;
    private final AuditService auditService;

    public AdminRetentionLogController(AdminRetentionLogService retentionLogs, UserContext userContext,
            AuditService auditService) {
        this.retentionLogs = retentionLogs;
        this.userContext = userContext;
        this.auditService = auditService;
    }

    @GetMapping
    public List<AdminRetentionLogView> listRetentionLogs(@RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        var admin = userContext.getUser();
        List<AdminRetentionLogView> views = retentionLogs.query(admin.tenantId(), userId, direction, protocol,
                AdminAuditController.parseInstant(from, "from"), AdminAuditController.parseInstant(to, "to"), page,
                size);
        auditService.record(admin.tenantId(), admin.id(), "RETENTION_LOG_VIEW", "RETENTION_LOG", null,
                "{\"rows\":" + views.size() + "}", null);
        return views;
    }

    @GetMapping(path = "/export", produces = "text/csv")
    public void exportRetentionLogsCsv(HttpServletResponse response, @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) throws IOException {
        var admin = userContext.getUser();
        AdminRetentionLogService.ExportResult result = retentionLogs.exportCsv(admin.tenantId(), userId, direction,
                protocol, AdminAuditController.parseInstant(from, "from"), AdminAuditController.parseInstant(to, "to"));
        auditService.record(admin.tenantId(), admin.id(), "RETENTION_LOG_EXPORT", "RETENTION_LOG", null,
                "{\"rows\":" + result.rows() + ",\"truncated\":" + result.truncated() + "}", null);

        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String stamp = Instant.now().toString().replace(":", "-").replace(".", "-");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"retention-logs-" + stamp + ".csv\"");
        if (result.truncated()) {
            response.setHeader("X-MiQroKey-Truncated", "true");
        }
        response.getWriter().write(result.csv());
    }
}
