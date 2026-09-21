package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AuditEventView;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService.AuditFilters;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService.ExportResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Open admin audit tail (ADR-0015, batch 1b read subset): the same causal-order
 * chain slice as the session endpoint, authenticated by an admin machine key
 * ({@code Authorization: Bearer mqk_admin_…}) or a SYSTEM_ADMIN session. Chain
 * hashes are never serialized. The compliance CSV export shares the session
 * endpoint's filters and truncation semantics ({@code usage:read} scope, since
 * the filter maps the whole {@code /audit-events} prefix).
 */
@RestController
@RequestMapping("/api/v1/admin-api/audit-events")
public class OpenAdminAuditReadController {

    private final AuditEventReadService auditEvents;

    public OpenAdminAuditReadController(AuditEventReadService auditEvents) {
        this.auditEvents = auditEvents;
    }

    @GetMapping
    public List<AuditEventView> list(HttpServletRequest request, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action, @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID actorId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Long beforePosition) {
        return auditEvents.list(
                tenantId(request), size, new AuditFilters(action, targetType, actorId,
                        AdminAuditController.parseInstant(from, "from"), AdminAuditController.parseInstant(to, "to")),
                beforePosition);
    }

    @GetMapping(path = "/export", produces = "text/csv")
    public void exportCsv(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(required = false) String action, @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID actorId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws IOException {
        ExportResult result = auditEvents.exportCsv(tenantId(request), new AuditFilters(action, targetType, actorId,
                AdminAuditController.parseInstant(from, "from"), AdminAuditController.parseInstant(to, "to")));
        AdminAuditController.writeCsv(response, result);
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }
}
