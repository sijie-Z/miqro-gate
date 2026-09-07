package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.AuditEventView;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AuditEventReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin audit events (G5.4, api-contract §5): immutable chain-links in reverse
 * causal order, with optional action filtering. The chain hashes are never
 * serialized (integrity proof lives in the table, not the API).
 * SYSTEM_ADMIN-only via the deny-by-default {@code /api/v1/admin/**}
 * interceptor.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-events")
public class AdminAuditController {

    private final AuditEventReadService auditEvents;
    private final UserContext userContext;

    public AdminAuditController(AuditEventReadService auditEvents, UserContext userContext) {
        this.auditEvents = auditEvents;
        this.userContext = userContext;
    }

    @GetMapping
    public List<AuditEventView> list(@RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action, @RequestParam(required = false) Long beforePosition) {
        return auditEvents.list(userContext.getUser().tenantId(), size, action, beforePosition);
    }
}
