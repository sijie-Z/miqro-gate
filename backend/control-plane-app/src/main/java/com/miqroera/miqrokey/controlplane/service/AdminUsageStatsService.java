package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.HourlyUsageReport;
import com.miqroera.miqrokey.controlplane.dto.HourlyUsageRow;
import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.usage.AdjustedUsageRow;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.HitAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-wide usage statistics (api-contract §5 {@code /api/v1/admin/usage/**},
 * G4.1): the same aggregation and record views as the self-service endpoints,
 * but over the whole tenant with optional filters on time, user, project,
 * Virtual Key, credential, subscription (Plan), provider product (vendor) and
 * model. Access is SYSTEM_ADMIN-only via the deny-by-default
 * {@code /api/v1/admin/**} interceptor.
 *
 * <p>
 * Records never carry prompt, code, or model content: only counts and metadata.
 * </p>
 */
@Service
public class AdminUsageStatsService {

    private static final int MAX_PAGE_SIZE = 200;
    /** Upper bound on page numbers (offset-overflow guard, #475). */
    private static final long MAX_PAGE = 1_000_000L;
    /** Hourly report window cap (#634): a week of 24h buckets. */
    private static final int MAX_HOURLY_DAYS = 7;

    private final UsageStatsRepository usageStatsRepository;

    public AdminUsageStatsService(UsageStatsRepository usageStatsRepository) {
        this.usageStatsRepository = usageStatsRepository;
    }

    /**
     * Aggregated usage and cost over the whole tenant.
     *
     * @param groupBy
     *            dimension from {@link UsageStatsRepository.GroupBy}
     *            (case-insensitive)
     * @param from
     *            inclusive start, null defaults to {@code to - 93 days}
     * @param to
     *            exclusive end, null defaults to now
     */
    /** Tenant-scoped summary for the system (billing) channel. */
    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to) {
        return summary(tenantId, groupBy, from, to, null, null, null, null, null, null, null);
    }

    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId) {
        return summary(tenantId, groupBy, from, to, userId, projectId, virtualKeyId, credentialId, subscriptionId,
                providerProductId, modelId, null);
    }

    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId,
            UUID teamId) {
        UsageStatsRepository.GroupBy dimension = UsageStatsService.parseGroupBy(groupBy);
        UsageStatsService.validateTimeRange(from, to);
        UsageStatsRepository.UsageFilter filter = adminFilter(tenantId, from, to, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId, null, teamId);
        List<UsageAggRow> usageRows = usageStatsRepository.aggregateUsage(dimension, filter);
        List<HitAggRow> hitRows = usageStatsRepository.aggregateHits(dimension, filter);
        return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), usageRows, hitRows);
    }

    public UsageSummary summary(User admin, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId,
            UUID teamId) {
        return summary(admin.tenantId(), groupBy, from, to, userId, projectId, virtualKeyId, credentialId,
                subscriptionId, providerProductId, modelId, teamId);
    }

    /**
     * Quota-watermark read path (#683): the same aggregation without the API's
     * 93-day window cap — an annual quota window legitimately spans the full
     * calendar year. Internal callers only; the cap still guards every public usage
     * endpoint.
     */
    public UsageSummary summaryUncapped(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId,
            UUID projectId) {
        UsageStatsRepository.GroupBy dimension = UsageStatsService.parseGroupBy(groupBy);
        UsageStatsRepository.UsageFilter filter = new UsageStatsRepository.UsageFilter(tenantId, null, userId,
                projectId, null, null, null, null, null, null, from, to);
        List<UsageAggRow> usageRows = usageStatsRepository.aggregateUsage(dimension, filter);
        List<HitAggRow> hitRows = usageStatsRepository.aggregateHits(dimension, filter);
        return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), usageRows, hitRows);
    }

    /** Paged raw usage records over the whole tenant, newest first. */
    /** Tenant-scoped records for the system (billing) channel. */
    public UsageRecordPage records(UUID tenantId, Instant from, Instant to, long page, int size) {
        return records(tenantId, from, to, page, size, null, null, null, null, null, null, null, null, null);
    }

    public UsageRecordPage records(UUID tenantId, Instant from, Instant to, long page, int size, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId, String clientIp) {
        return records(tenantId, from, to, page, size, userId, projectId, virtualKeyId, credentialId, subscriptionId,
                providerProductId, modelId, clientIp, null);
    }

    public UsageRecordPage records(UUID tenantId, Instant from, Instant to, long page, int size, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId, String clientIp, UUID teamId) {
        if (page < 1 || page > MAX_PAGE) {
            // #475: an unchecked huge page overflows (page-1)*size into a negative
            // SQL OFFSET; bound it as a client error instead.
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "page must be between 1 and " + MAX_PAGE);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIZE_INVALID",
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        UsageStatsRepository.UsageFilter filter = adminFilter(tenantId, from, to, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId, clientIp, teamId);
        return recordsFor(tenantId, filter, page, size);
    }

    public UsageRecordPage records(User admin, Instant from, Instant to, long page, int size, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId, String clientIp, UUID teamId) {
        return records(admin.tenantId(), from, to, page, size, userId, projectId, virtualKeyId, credentialId,
                subscriptionId, providerProductId, modelId, clientIp, teamId);
    }

    private UsageRecordPage recordsFor(UUID tenantId, UsageStatsRepository.UsageFilter filter, long page, int size) {
        long total = usageStatsRepository.countRecords(filter);
        List<AdjustedUsageRow> events = usageStatsRepository.findRecords(filter, (page - 1) * size, size);
        List<UsageRecordPage.UsageRecordView> items = new ArrayList<>(events.size());
        for (AdjustedUsageRow row : events) {
            items.add(view(row));
        }
        return new UsageRecordPage(items, page, size, total);
    }

    // -------------------------------------------------------------------

    /**
     * Hourly token table (#634): per-hour buckets over a natural-day window in the
     * caller's timezone, always crossed with the project and optionally with the
     * user or team. Only buckets that saw usage are returned; empty hours are the
     * caller's to fill in.
     *
     * @param date
     *            end date {@code YYYY-MM-DD} in {@code tzOffsetMinutes}; null
     *            defaults to "today" there
     * @param days
     *            consecutive days ending at {@code date}, 1..7; null defaults to 1
     * @param dimension
     *            {@code NONE} | {@code USER} | {@code TEAM} (case-insensitive);
     *            null defaults to {@code NONE}
     * @param tzOffsetMinutes
     *            offset from UTC in minutes for day/hour boundaries; null defaults
     *            to 0 (UTC)
     */
    public HourlyUsageReport hourly(User admin, String date, Integer days, String dimension, UUID userId,
            UUID projectId, Integer tzOffsetMinutes, UUID teamId) {
        int tz = tzOffsetMinutes == null ? 0 : tzOffsetMinutes;
        if (tz < -18 * 60 || tz > 18 * 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TZ_OFFSET_INVALID",
                    "tzOffsetMinutes must be between -1080 and 1080");
        }
        int span = days == null ? 1 : days;
        if (span < 1 || span > MAX_HOURLY_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DAYS_INVALID",
                    "days must be between 1 and " + MAX_HOURLY_DAYS);
        }
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(tz * 60);
        LocalDate end;
        try {
            end = date == null || date.isBlank() ? LocalDate.now(offset) : LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATE_INVALID", "date must be YYYY-MM-DD");
        }
        UsageStatsRepository.HourlyDimension dim = parseHourlyDimension(dimension);
        Instant from = end.minusDays(span - 1L).atStartOfDay(offset).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(offset).toInstant();
        UsageStatsRepository.UsageFilter filter = new UsageStatsRepository.UsageFilter(admin.tenantId(), null, userId,
                projectId, null, null, null, null, null, teamId, from, to);
        List<UsageStatsRepository.HourlyUsageRow> rows = usageStatsRepository.aggregateHourly(dim, filter, tz);
        List<HourlyUsageRow> items = new ArrayList<>(rows.size());
        for (UsageStatsRepository.HourlyUsageRow r : rows) {
            items.add(new HourlyUsageRow(r.hourStart(), r.projectId(), r.projectLabel(), r.dimensionId(),
                    r.dimensionLabel(), r.requests(), r.inputTokens(), r.outputTokens(), r.cacheReadTokens(),
                    r.cacheCreationTokens(), r.totalTokens()));
        }
        return new HourlyUsageReport(end.toString(), span, dim.name(), tz, items);
    }

    private static UsageStatsRepository.HourlyDimension parseHourlyDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return UsageStatsRepository.HourlyDimension.NONE;
        }
        try {
            return UsageStatsRepository.HourlyDimension.valueOf(dimension.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DIMENSION_INVALID",
                    "dimension must be one of NONE, USER, TEAM");
        }
    }

    /**
     * Admin scope: no key-set restriction (key set is {@code null}); every
     * dimension is an optional filter. Tenant scoping still comes from the
     * authenticated admin — there is no cross-tenant query shape.
     */
    private static UsageStatsRepository.UsageFilter adminFilter(UUID tenantId, Instant from, Instant to, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId, String clientIp, UUID teamId) {
        UsageStatsService.validateTimeRange(from, to);
        Instant toResolved = to == null ? Instant.now() : to;
        Instant fromResolved = from == null ? toResolved.minus(UsageStatsService.MAX_WINDOW) : from;
        return new UsageStatsRepository.UsageFilter(tenantId, virtualKeyId != null ? Set.of(virtualKeyId) : null,
                userId, projectId, credentialId, subscriptionId, providerProductId,
                modelId != null && !modelId.isBlank() ? modelId : null,
                clientIp != null && !clientIp.isBlank() ? clientIp.trim() : null, teamId, fromResolved, toResolved);
    }

    /**
     * Maps one row to the wire shape. The observed counts stay exactly the fact the
     * gateway recorded; the net counts and the {@code adjusted} marker ride
     * alongside so a reader can always tell a corrected row from an untouched one
     * (#709).
     */
    private static UsageRecordPage.UsageRecordView view(AdjustedUsageRow row) {
        UsageEvent e = row.observed();
        TokenBucket t = e.tokens();
        Long input = orNull(t != null ? t.inputTokens() : null, t != null ? t.promptTokens() : null);
        Long output = orNull(t != null ? t.outputTokens() : null, t != null ? t.completionTokens() : null);
        return new UsageRecordPage.UsageRecordView(e.occurredAt(), e.modelId(), e.cacheLevel(), input, output,
                t != null ? t.cacheReadInputTokens() : null, t != null ? t.cacheCreationInputTokens() : null,
                t != null ? t.totalTokens() : null, e.latencyMs(), e.upstreamStatusCode(), e.providerRequestId(),
                e.gatewayRequestId(), e.isComplete(), e.usageMissing(), e.virtualKeyId(), e.clientIp(),
                row.netInputTokens(), row.netOutputTokens(), row.netCacheReadInputTokens(),
                row.netCacheCreationInputTokens(), row.adjusted());
    }

    /** Primary input/output token, preferring the protocol-specific column. */
    private static Long orNull(Long primary, Long fallback) {
        return primary != null ? primary : fallback;
    }
}
