package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.ApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminUsageStatsService;
import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.UUID;

/**
 * Billing query API for external systems (ADR-0010). Protected by
 * {@link ApiKeyAuthFilter} (consumer API key or portal admin session). Returns
 * metadata only — no prompts, code or model content.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final AdminUsageStatsService usageStatsService;
    private final UserContext userContext;

    public BillingController(AdminUsageStatsService usageStatsService, UserContext userContext) {
        this.usageStatsService = usageStatsService;
        this.userContext = userContext;
    }

    @GetMapping("/summary")
    public UsageSummary summary(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            HttpServletRequest request) {
        UUID tenantId = tenant(request);
        return usageStatsService.summary(tenantId, groupBy, iso(from), iso(to));
    }

    @GetMapping("/records")
    public UsageRecordPage records(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") int size, HttpServletRequest request) {
        UUID tenantId = tenant(request);
        return usageStatsService.records(tenantId, iso(from), iso(to), page, size);
    }

    private UUID tenant(HttpServletRequest request) {
        Object tenantId = request.getAttribute(ApiKeyAuthFilter.TENANT_ATTR);
        if (tenantId != null) {
            return (UUID) tenantId;
        }
        return userContext.getUser().tenantId();
    }

    private static Instant iso(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
