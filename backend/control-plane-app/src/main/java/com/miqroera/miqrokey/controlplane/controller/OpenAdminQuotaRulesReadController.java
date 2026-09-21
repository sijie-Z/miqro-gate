package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.QuotaRuleView;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.AdminQuotaRuleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Open quota plan read (ADR-0015, batch 1b read subset): all quota rules with
 * live watermarks for their current windows — alerting-only governance views,
 * never blocking. Authenticated by an admin machine key or a SYSTEM_ADMIN
 * session.
 */
@RestController
@RequestMapping("/api/v1/admin-api/quota-rules")
public class OpenAdminQuotaRulesReadController {

    private final AdminQuotaRuleService quotaRuleService;

    public OpenAdminQuotaRulesReadController(AdminQuotaRuleService quotaRuleService) {
        this.quotaRuleService = quotaRuleService;
    }

    @GetMapping
    public List<QuotaRuleView> list(HttpServletRequest request) {
        return quotaRuleService.list(tenantId(request));
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }
}
