package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.UsageStatsService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Self-service usage statistics (api-contract §4): aggregated summary with
 * cost, and paged raw records. Both are scoped to the caller's own Virtual Keys
 * — records never include prompt, code, or model content.
 */
@RestController
@RequestMapping("/api/v1/me/usage")
public class MeUsageController {

    private final UsageStatsService usageStatsService;
    private final UserContext userContext;

    public MeUsageController(UsageStatsService usageStatsService, UserContext userContext) {
        this.usageStatsService = usageStatsService;
        this.userContext = userContext;
    }

    /**
     * Aggregated usage and cost, e.g.
     * {@code GET /api/v1/me/usage/summary?groupBy=VIRTUAL_KEY&from=...&to=...}.
     *
     * @param groupBy
     *            PROJECT | VIRTUAL_KEY | CACHE_LEVEL | DAY (default PROJECT)
     */
    @GetMapping("/summary")
    public UsageSummary summary(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return usageStatsService.summary(userContext.getUser(), groupBy, from, to);
    }

    /**
     * Paged raw usage records, newest first, e.g.
     * {@code GET /api/v1/me/usage/records?from=...&to=...&page=1&size=50}. The
     * window is capped at
     * {@value com.miqroera.miqrokey.controlplane.service.UsageStatsService#MAX_WINDOW}.
     */
    @GetMapping("/records")
    public UsageRecordPage records(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        return usageStatsService.records(userContext.getUser(), from, to, page, size);
    }
}
