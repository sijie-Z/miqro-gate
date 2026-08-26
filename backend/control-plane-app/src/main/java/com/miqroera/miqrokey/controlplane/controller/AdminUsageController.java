package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminUsageStatsService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-wide usage statistics (api-contract §5, G4.1): aggregated summary and
 * paged raw records over the whole tenant, filterable by time, user, project,
 * Virtual Key, credential, subscription (Plan), provider product (vendor) and
 * model. Access is SYSTEM_ADMIN-only via the deny-by-default
 * {@code /api/v1/admin/**} interceptor. Records never include prompt, code, or
 * model content.
 */
@RestController
@RequestMapping("/api/v1/admin/usage")
public class AdminUsageController {

    private final AdminUsageStatsService usageStatsService;
    private final UserContext userContext;

    public AdminUsageController(AdminUsageStatsService usageStatsService, UserContext userContext) {
        this.usageStatsService = usageStatsService;
        this.userContext = userContext;
    }

    /**
     * Aggregated usage and cost, e.g.
     * {@code GET /api/v1/admin/usage/summary?groupBy=DAY&userId=...&modelId=...}.
     *
     * @param groupBy
     *            PROJECT | VIRTUAL_KEY | CACHE_LEVEL | DAY (default PROJECT)
     */
    @GetMapping("/summary")
    public UsageSummary summary(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID userId, @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID virtualKeyId, @RequestParam(required = false) UUID credentialId,
            @RequestParam(required = false) UUID subscriptionId, @RequestParam(required = false) UUID providerProductId,
            @RequestParam(required = false) String modelId) {
        return usageStatsService.summary(userContext.getUser(), groupBy, from, to, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId);
    }

    /**
     * Paged raw usage records over the whole tenant, newest first, e.g.
     * {@code GET /api/v1/admin/usage/records?from=...&to=...&page=1&size=50&projectId=...}.
     * The window is capped at
     * {@value com.miqroera.miqrokey.controlplane.service.UsageStatsService#MAX_WINDOW}.
     */
    @GetMapping("/records")
    public UsageRecordPage records(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "50") int size, @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID projectId, @RequestParam(required = false) UUID virtualKeyId,
            @RequestParam(required = false) UUID credentialId, @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(required = false) UUID providerProductId, @RequestParam(required = false) String modelId) {
        return usageStatsService.records(userContext.getUser(), from, to, page, size, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId);
    }
}
