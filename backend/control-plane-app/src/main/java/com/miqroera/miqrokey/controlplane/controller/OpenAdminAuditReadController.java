package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AuditEventView;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Open admin audit tail (ADR-0015, batch 1b read subset): the same causal-order
 * chain slice as the session endpoint, authenticated by an admin machine key
 * ({@code Authorization: Bearer mqk_admin_…}) or a SYSTEM_ADMIN session. Chain
 * hashes are never serialized.
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
            @RequestParam(required = false) String action, @RequestParam(required = false) Long beforePosition) {
        return auditEvents.list(tenantId(request), size, action, beforePosition);
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }
}
