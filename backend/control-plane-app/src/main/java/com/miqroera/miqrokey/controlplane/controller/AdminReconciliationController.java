package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditContext;
import com.miqroera.miqrokey.controlplane.service.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Bill reconciliation (issue #334, F19 contract draft v0): canonical JSONL
 * upload creates an async four-state report; metadata and detail rows are
 * read-only. SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
public class AdminReconciliationController {

    private final ReconciliationService reconciliationService;
    private final UserContext userContext;

    public AdminReconciliationController(ReconciliationService reconciliationService, UserContext userContext) {
        this.reconciliationService = reconciliationService;
        this.userContext = userContext;
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

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
