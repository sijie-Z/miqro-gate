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

    /** Same with the caller's local day (#1050). */
    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to, Integer tzOffsetMinutes) {
        return summary(tenantId, groupBy, from, to, null, null, null, null, null, null, null, null, tzOffsetMinutes);
    }

    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId) {
        // (UUID) cast: the tail null is now ambiguous between the team id and the
        // tz offset overloads (#1050).
        return summary(tenantId, groupBy, from, to, userId, projectId, virtualKeyId, credentialId, subscriptionId,
                providerProductId, modelId, (UUID) null);
    }

    /** Same with the caller's local day (#1050). */
    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId,
            Integer tzOffsetMinutes) {
        return summary(tenantId, groupBy, from, to, userId, projectId, virtualKeyId, credentialId, subscriptionId,
                providerProductId, modelId, null, tzOffsetMinutes);
    }

    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId,
            UUID teamId) {
        return summary(tenantId, groupBy, from, to, userId, projectId, virtualKeyId, credentialId, subscriptionId,
                providerProductId, modelId, teamId, null);
    }

    /**
     * Same summary, with {@code day}/{@code month} buckets aligned to the caller's
     * local day (#1050): {@code tzOffsetMinutes} is the caller's fixed offset from
     * UTC (null = UTC) — the same parameter the hourly report takes. Without it the
     * console's trend axis (UTC days) disagreed with the request log beside it
     * (local timestamps), so local 00:00–08:00 traffic was drawn on the previous
     * bar.
     */
    public UsageSummary summary(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId,
            UUID teamId, Integer tzOffsetMinutes) {
        UsageStatsRepository.GroupBy dimension = UsageStatsService.parseGroupBy(groupBy);
        UsageStatsService.validateTimeRange(from, to);
        int tz = tzOffset(tzOffsetMinutes);
        UsageStatsRepository.UsageFilter filter = adminFilter(tenantId, from, to, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId, null, teamId);
        List<UsageAggRow> usageRows = usageStatsRepository.aggregateUsage(dimension, filter, tz);
        List<HitAggRow> hitRows = usageStatsRepository.aggregateHits(dimension, filter, tz);
        return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), usageRows, hitRows);
    }

    public UsageSummary summary(User admin, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId,
            UUID teamId) {
        return summary(admin.tenantId(), groupBy, from, to, userId, projectId, virtualKeyId, credentialId,
                subscriptionId, providerProductId, modelId, teamId);
    }

    /**
     * Same as above with the caller's local day (#1050); see the tenant-scoped
     * variant.
     */
    public UsageSummary summary(User admin, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId,
            UUID teamId, Integer tzOffsetMinutes) {
        return summary(admin.tenantId(), groupBy, from, to, userId, projectId, virtualKeyId, credentialId,
                subscriptionId, providerProductId, modelId, teamId, tzOffsetMinutes);
    }

    /**
     * The one offset parser for the reporting endpoints (#1050), mirroring the
     * hourly report's rule: null = UTC, and anything outside ±18h is a client bug,
     * not a timezone.
     */
    static int tzOffset(Integer tzOffsetMinutes) {
        int tz = tzOffsetMinutes == null ? 0 : tzOffsetMinutes;
        if (tz < -18 * 60 || tz > 18 * 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TZ_OFFSET_INVALID",
                    "tzOffsetMinutes must be between -1080 and 1080");
        }
        return tz;
    }

    /**
     * Quota-watermark read path (#683): the same aggregation without the API's
     * 93-day window cap — an annual quota window legitimately spans the full
     * calendar year. Internal callers only; the cap still guards every public usage
     * endpoint.
     *
     * <p>
     * Reads the <b>observed</b> token columns (#1002): a watermark is a runtime
     * verdict on what the gateway actually counted, so the booking ledger (#709)
     * must not move it. Reporting stays on the adjusted reading.
     * </p>
     */
    public UsageSummary summaryObservedUncapped(UUID tenantId, String groupBy, Instant from, Instant to, UUID userId,
            UUID projectId) {
        UsageStatsRepository.GroupBy dimension = UsageStatsService.parseGroupBy(groupBy);
        UsageStatsRepository.UsageFilter filter = new UsageStatsRepository.UsageFilter(tenantId, null, userId,
                projectId, null, null, null, null, null, null, from, to);
        List<UsageAggRow> usageRows = usageStatsRepository.aggregateObservedUsage(dimension, filter);
        List<HitAggRow> hitRows = usageStatsRepository.aggregateHits(dimension, filter);
        return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), usageRows, hitRows);
    }

    /** Paged raw usage records over the whole tenant, newest first. */
    /** Tenant-scoped records for the system (billing) channel. */
    public UsageRecordPage records(UUID tenantId, Instant from, Instant to, long page, int size) {
        return records(tenantId, from, to, page, size, null);
    }

    /**
     * The same page, continued from an opaque {@code before} cursor (#1368) — what
     * the console's export walks with. Null/blank means "start at the newest row".
     */
    public UsageRecordPage records(UUID tenantId, Instant from, Instant to, long page, int size, String before) {
        return records(tenantId, from, to, page, size, null, null, null, null, null, null, null, null, null, before);
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
        return records(tenantId, from, to, page, size, userId, projectId, virtualKeyId, credentialId, subscriptionId,
                providerProductId, modelId, clientIp, teamId, null);
    }

    public UsageRecordPage records(UUID tenantId, Instant from, Instant to, long page, int size, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId, String clientIp, UUID teamId, String before) {
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
        return recordsFor(tenantId, filter, page, size, UsageRecordCursor.of(before));
    }

    public UsageRecordPage records(User admin, Instant from, Instant to, long page, int size, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId, String clientIp, UUID teamId) {
        return records(admin, from, to, page, size, userId, projectId, virtualKeyId, credentialId, subscriptionId,
                providerProductId, modelId, clientIp, teamId, null);
    }

    public UsageRecordPage records(User admin, Instant from, Instant to, long page, int size, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId, String clientIp, UUID teamId, String before) {
        return records(admin.tenantId(), from, to, page, size, userId, projectId, virtualKeyId, credentialId,
                subscriptionId, providerProductId, modelId, clientIp, teamId, before);
    }

    /**
     * One page of records plus the cursor that continues it (#1368).
     *
     * <p>
     * {@code total} is a separate exact {@code COUNT(*)} over the same filter, as
     * before — it is what the console's pager shows ("共 N 条"). It is a second read,
     * so on a table the gateway is still writing it can differ from what a walk
     * ends up handing out; {@code nextCursor} is what says whether there is more,
     * not the total.
     * </p>
     *
     * <p>
     * Two windows. A cursor — and page 1, where a walk starts — reads the keyset
     * window and over-fetches one row to learn whether another page exists. An
     * explicit {@code page > 1} is a jump-to-page request: the caller asked for
     * "roughly here", so it keeps the offset window, which is the only thing that
     * can answer it. Either way the rows come back in the same total order
     * ({@code occurred_at DESC, id DESC}), and the cursor handed out is the last
     * row returned, so a walk started from page 1 stays exact from then on.
     * </p>
     */
    private UsageRecordPage recordsFor(UUID tenantId, UsageStatsRepository.UsageFilter filter, long page, int size,
            UsageRecordCursor cursor) {
        long total = usageStatsRepository.countRecords(filter);
        boolean keyset = cursor.occurredAt() != null || page == 1;
        List<AdjustedUsageRow> events = keyset
                ? usageStatsRepository.findRecords(filter, size + 1, cursor.occurredAt(), cursor.id())
                : usageStatsRepository.findRecords(filter, (page - 1) * size, size);
        int returned = Math.min(events.size(), size);
        List<UsageRecordPage.UsageRecordView> items = new ArrayList<>(returned);
        for (int i = 0; i < returned; i++) {
            items.add(view(events.get(i)));
        }
        // Keyset: the extra row is the whole answer to "is there more". Offset: a
        // short page is the only signal available — a full one may or may not have
        // a successor, and the cursor says it safely either way.
        boolean hasMore = keyset ? events.size() > size : events.size() == size;
        AdjustedUsageRow last = returned == 0 ? null : events.get(returned - 1);
        String nextCursor = hasMore && last != null
                ? UsageRecordCursor.encode(last.observed().occurredAt(), last.observed().id())
                : null;
        return new UsageRecordPage(items, page, size, total, nextCursor);
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
     * (#709). The per-row cost is priced from the row's own price basis — the same
     * one the aggregates price with (#710) — and flagged {@code priced=false} when
     * that basis does not cover a dimension the row used.
     */
    private static UsageRecordPage.UsageRecordView view(AdjustedUsageRow row) {
        UsageEvent e = row.observed();
        TokenBucket t = e.tokens();
        Long input = orNull(t != null ? t.inputTokens() : null, t != null ? t.promptTokens() : null);
        Long output = orNull(t != null ? t.outputTokens() : null, t != null ? t.completionTokens() : null);
        Long cacheRead = t != null ? t.cacheReadInputTokens() : null;
        Long cacheCreation = t != null ? t.cacheCreationInputTokens() : null;
        UsageStatsAggregator.PricedCost priced = UsageStatsAggregator.pricedCost(row.priceBasis(), input, output,
                cacheRead, cacheCreation);
        // #1128: the ruling travels with the claim it judged — the console shows both,
        // so a
        // row whose client claimed one project and was ruled to another is visible as
        // such.
        UsageEvent.ContextAttribution attribution = e.attribution();
        return new UsageRecordPage.UsageRecordView(e.occurredAt(), e.modelId(), e.cacheLevel(), input, output,
                cacheRead, cacheCreation, t != null ? t.totalTokens() : null, e.latencyMs(), e.upstreamStatusCode(),
                e.providerRequestId(), e.gatewayRequestId(), e.isComplete(), e.usageMissing(), e.virtualKeyId(),
                e.clientIp(), row.netInputTokens(), row.netOutputTokens(), row.netCacheReadInputTokens(),
                row.netCacheCreationInputTokens(), row.adjusted(), row.providerProductName(),
                row.lifecycle() != null ? row.lifecycle().timeToFirstByteMs() : null,
                row.lifecycle() != null ? row.lifecycle().wireProtocol() : null,
                row.lifecycle() != null ? row.lifecycle().requestStatus() : null, priced.cost(), priced.priced(),
                attribution != null ? attribution.resolutionStatus() : null,
                attribution != null ? attribution.resolutionCandidates() : null,
                attribution != null ? attribution.claimSource() : null,
                attribution != null ? attribution.claimConfidence() : null);
    }

    /** Primary input/output token, preferring the protocol-specific column. */
    private static Long orNull(Long primary, Long fallback) {
        return primary != null ? primary : fallback;
    }
}
