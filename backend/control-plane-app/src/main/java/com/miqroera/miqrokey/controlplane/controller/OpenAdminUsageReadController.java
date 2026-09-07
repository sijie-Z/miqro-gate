package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.service.AdminUsageStatsService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only open admin surface (ADR-0015, batch 1): tenant-scoped usage summary
 * and records authenticated by an admin machine key
 * ({@code Authorization: Bearer mqk_admin_…}). Subset by design — writes and
 * further reads extend in batch 1b/2 after the machine-executor semantics are
 * settled.
 */
@RestController
@RequestMapping("/api/v1/admin-api/usage")
public class OpenAdminUsageReadController {

    private final AdminUsageStatsService usageStatsService;

    public OpenAdminUsageReadController(AdminUsageStatsService usageStatsService) {
        this.usageStatsService = usageStatsService;
    }

    @GetMapping("/summary")
    public UsageSummary summary(HttpServletRequest request, @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return usageStatsService.summary(tenantId(request), groupBy, from, to);
    }

    @GetMapping("/records")
    public UsageRecordPage records(HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        return usageStatsService.records(tenantId(request), from, to, page, size);
    }

    private static UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AdminApiKeyAuthFilter.TENANT_ATTR);
    }
}
