package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.HourlyUsageReport;
import com.miqroera.miqrokey.controlplane.dto.ModelCallTimelineView;
import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.AdminUsageStatsService;
import com.miqroera.miqrokey.controlplane.service.ModelCallTimelineService;
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
    private final ModelCallTimelineService modelCallTimelineService;
    private final UserContext userContext;

    public AdminUsageController(AdminUsageStatsService usageStatsService,
            ModelCallTimelineService modelCallTimelineService, UserContext userContext) {
        this.usageStatsService = usageStatsService;
        this.modelCallTimelineService = modelCallTimelineService;
        this.userContext = userContext;
    }

    /**
     * One model call's lifecycle timeline (#705), e.g.
     * {@code GET /api/v1/admin/usage/timeline?gatewayRequestId=...}.
     *
     * <p>
     * Replays the phases the gateway already records (受理 → 上游首字节 → 完成) for a single
     * call, so "这次调用卡在哪一步" is answerable from the console. Calls that never reached
     * upstream (cache hits, auth/model rejections) are not written to the lifecycle
     * table and return 404.
     * </p>
     */
    @GetMapping("/timeline")
    public ModelCallTimelineView timeline(@RequestParam(required = false) String gatewayRequestId) {
        return modelCallTimelineService.timeline(userContext.getUser(), gatewayRequestId);
    }

    /**
     * Aggregated usage and cost, e.g.
     * {@code GET /api/v1/admin/usage/summary?groupBy=DAY&userId=...&modelId=...}.
     *
     * @param groupBy
     *            PROJECT | VIRTUAL_KEY | CACHE_LEVEL | DAY | USER | MODEL | MONTH
     *            (default PROJECT)
     * @param tzOffsetMinutes
     *            offset from UTC in minutes for {@code DAY}/{@code MONTH} buckets
     *            (#1050); null = UTC. Same parameter the hourly report takes.
     */
    @GetMapping("/summary")
    public UsageSummary summary(@RequestParam(required = false) String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID userId, @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID virtualKeyId, @RequestParam(required = false) UUID credentialId,
            @RequestParam(required = false) UUID subscriptionId, @RequestParam(required = false) UUID providerProductId,
            @RequestParam(required = false) String modelId, @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) Integer tzOffsetMinutes) {
        return usageStatsService.summary(userContext.getUser(), groupBy, from, to, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId, teamId, tzOffsetMinutes);
    }

    /**
     * Paged raw usage records over the whole tenant, newest first, e.g.
     * {@code GET /api/v1/admin/usage/records?from=...&to=...&page=1&size=50&projectId=...}.
     * The window is capped at
     * {@value com.miqroera.miqrokey.controlplane.service.UsageStatsService#MAX_WINDOW}.
     *
     * <p>
     * {@code page} is jump-to-page; pass {@code before} instead to walk the list
     * and get every row exactly once (#1368), taking it from the previous
     * response's {@code nextCursor} and stopping when it comes back null. The
     * cursor wins if both are given.
     * </p>
     */
    @GetMapping("/records")
    public UsageRecordPage records(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "50") int size, @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID projectId, @RequestParam(required = false) UUID virtualKeyId,
            @RequestParam(required = false) UUID credentialId, @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(required = false) UUID providerProductId, @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String clientIp, @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String before) {
        return usageStatsService.records(userContext.getUser(), from, to, page, size, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId, clientIp, teamId, before);
    }

    /**
     * Hourly token table (#634), e.g.
     * {@code GET /api/v1/admin/usage/hourly?date=2026-09-16&dimension=USER&tzOffsetMinutes=480}.
     * Always crossed with the project; {@code dimension} adds the user or team
     * grouping. Only hours with usage are returned; window is 1..7 days ending at
     * {@code date} (default: today in {@code tzOffsetMinutes}, default UTC).
     */
    @GetMapping("/hourly")
    public HourlyUsageReport hourly(@RequestParam(required = false) String date,
            @RequestParam(required = false) Integer days, @RequestParam(required = false) String dimension,
            @RequestParam(required = false) UUID userId, @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Integer tzOffsetMinutes, @RequestParam(required = false) UUID teamId) {
        return usageStatsService.hourly(userContext.getUser(), date, days, dimension, userId, projectId,
                tzOffsetMinutes, teamId);
    }
}
