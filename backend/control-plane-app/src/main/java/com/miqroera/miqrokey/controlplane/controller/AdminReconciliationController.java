package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import com.miqroera.miqrokey.controlplane.service.ReconciliationService;
import com.miqroera.miqrokey.domain.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Bill reconciliation (issue #334, F19 contract draft v0): canonical JSONL
 * upload creates an async four-state report; the report list, metadata and
 * detail rows are read-only. SYSTEM_ADMIN-only via the deny-by-default
 * {@code /api/v1/admin/**} interceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
public class AdminReconciliationController {

    private final ReconciliationService reconciliationService;
    private final UserContext userContext;
    private final AuditService auditService;

    public AdminReconciliationController(ReconciliationService reconciliationService, UserContext userContext,
            AuditService auditService) {
        this.reconciliationService = reconciliationService;
        this.userContext = userContext;
        this.auditService = auditService;
    }

    /** Uploads a canonical bill (JSONL, optional gzip); 202 + the report. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestParam String providerCode, @RequestParam String currency,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant windowFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant windowTo,
            @RequestBody(required = false) byte[] body, HttpServletRequest httpReq) {
        var user = userContext.getUser();
        Map<String, Object> report = reconciliationService.create(user.tenantId(),
                AuditContext.human(user.id(), requestId(httpReq)), providerCode, currency, windowFrom, windowTo,
                body == null ? new byte[0] : body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(report);
    }

    /** Tenant reports, newest first; limit 1..100 (default 20). */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit) {
        return reconciliationService.list(userContext.getUser().tenantId(), limit);
    }

    @GetMapping("/{reportId}")
    public Map<String, Object> get(@PathVariable UUID reportId) {
        return reconciliationService.get(userContext.getUser().tenantId(), reportId);
    }

    /** Four-state detail rows, filterable by state with row_no cursor paging. */
    @GetMapping("/{reportId}/rows")
    public Map<String, Object> rows(@PathVariable UUID reportId, @RequestParam(required = false) String state,
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "100") int limit) {
        return reconciliationService.rows(userContext.getUser().tenantId(), reportId, state, cursor, limit);
    }

    /**
     * Four-state detail rows of one report as CSV (api-contract §5.27), with the
     * same synchronous download shape as the audit and retention-log exports: 5 万行
     * cap declared through {@code X-MiQroKey-Truncated}, exact row count through
     * {@code X-MiQroKey-Rows}. Exports are audited before the body is written, so a
     * truncated or failed response still leaves a trace.
     */
    @GetMapping(path = "/{reportId}/export", produces = "text/csv")
    public void exportRowsCsv(@PathVariable UUID reportId, @RequestParam(required = false) String state,
            HttpServletResponse response, HttpServletRequest httpReq) throws IOException {
        var admin = userContext.getUser();
        ReconciliationService.CsvExport result = reconciliationService.exportCsv(admin.tenantId(), reportId, state);
        auditService.record(admin.tenantId(), admin.id(), "RECONCILIATION_EXPORT", "RECONCILIATION", reportId,
                "{\"rows\":" + result.rows() + ",\"truncated\":" + result.truncated() + "}", requestId(httpReq));

        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String stamp = Instant.now().toString().replace(":", "-").replace(".", "-");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"reconciliation-" + reportId + "-" + stamp + ".csv\"");
        response.setHeader("X-MiQroKey-Rows", Integer.toString(result.rows()));
        if (result.truncated()) {
            response.setHeader("X-MiQroKey-Truncated", "true");
        }
        response.getWriter().write(result.csv());
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
