package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.usage.AdjustedUsageRow;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.HitAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Self-service usage statistics for one user (api-contract §4): aggregated
 * summary with cost, and paged raw records. Everything is scoped to the
 * caller's own Virtual Keys — usage that flows through keys the user does not
 * own is invisible, as if it did not exist.
 *
 * <p>
 * Records never carry prompt, code, or model content: only counts and metadata.
 * </p>
 */
@Service
public class UsageStatsService {

    /** Longest window a single query may cover (records and summary alike). */
    static final Duration MAX_WINDOW = Duration.ofDays(93);
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final VirtualKeyRepository keyRepository;
    private final UsageStatsRepository usageStatsRepository;

    public UsageStatsService(VirtualKeyRepository keyRepository, UsageStatsRepository usageStatsRepository) {
        this.keyRepository = keyRepository;
        this.usageStatsRepository = usageStatsRepository;
    }

    /**
     * Aggregated usage and cost for the caller's own keys.
     *
     * @param groupBy
     *            dimension name from {@link UsageStatsRepository.GroupBy}
     *            (case-insensitive)
     * @param from
     *            inclusive start, null defaults to {@code to - 93 days}
     * @param to
     *            exclusive end, null defaults to now
     */
    public UsageSummary summary(User user, String groupBy, Instant from, Instant to) {
        return summary(user, groupBy, from, to, null);
    }

    /**
     * Same summary, with {@code day}/{@code month} buckets in the caller's local
     * day (#1050): {@code tzOffsetMinutes} is the caller's fixed offset from UTC
     * (null = UTC), mirroring the hourly report's parameter.
     */
    public UsageSummary summary(User user, String groupBy, Instant from, Instant to, Integer tzOffsetMinutes) {
        UsageStatsRepository.GroupBy dimension = parseGroupBy(groupBy);
        validateTimeRange(from, to);
        int tz = AdminUsageStatsService.tzOffset(tzOffsetMinutes);
        Set<UUID> keyIds = ownKeyIds(user);
        if (keyIds.isEmpty()) {
            // No keys to aggregate — return a zeroed summary without touching the
            // usage tables.
            return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), List.of(), List.of());
        }
        UsageStatsRepository.UsageFilter filter = filter(user, keyIds, from, to);

        List<UsageAggRow> usageRows = usageStatsRepository.aggregateUsage(dimension, filter, tz);
        List<HitAggRow> hitRows = usageStatsRepository.aggregateHits(dimension, filter, tz);
        return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), usageRows, hitRows);
    }

    /** Paged raw usage records for the caller's own keys, newest first. */
    public UsageRecordPage records(User user, Instant from, Instant to, long page, int size) {
        return records(user, from, to, page, size, null);
    }

    /**
     * The same page, continued from an opaque {@code before} cursor (#1368) —
     * what the console's export walks with. Null/blank means "start at the newest
     * row"; see {@link AdminUsageStatsService#records} for the paging rule, which
     * is the same one.
     */
    public UsageRecordPage records(User user, Instant from, Instant to, long page, int size, String before) {
        if (page < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIZE_INVALID",
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        validateTimeRange(from, to);
        UsageRecordCursor cursor = UsageRecordCursor.of(before);
        Set<UUID> keyIds = ownKeyIds(user);
        if (keyIds.isEmpty()) {
            return new UsageRecordPage(List.of(), page, size, 0L, null);
        }
        UsageStatsRepository.UsageFilter filter = filter(user, keyIds, from, to);

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
        boolean hasMore = keyset ? events.size() > size : events.size() == size;
        AdjustedUsageRow last = returned == 0 ? null : events.get(returned - 1);
        String nextCursor = hasMore && last != null
                ? UsageRecordCursor.encode(last.observed().occurredAt(), last.observed().id())
                : null;
        return new UsageRecordPage(items, page, size, total, nextCursor);
    }

    // -------------------------------------------------------------------

    private Set<UUID> ownKeyIds(User user) {
        Set<UUID> ids = new HashSet<>();
        for (VirtualKey key : keyRepository.findAllByUserId(user.id())) {
            ids.add(key.id());
        }
        return ids;
    }

    private UsageStatsRepository.UsageFilter filter(User user, Set<UUID> keyIds, Instant from, Instant to) {
        validateTimeRange(from, to);
        Instant toResolved = to == null ? Instant.now() : to;
        Instant fromResolved = from == null ? toResolved.minus(MAX_WINDOW) : from;
        return new UsageStatsRepository.UsageFilter(user.tenantId(), keyIds, fromResolved, toResolved);
    }

    /**
     * Window validation is unconditional — it applies even when the caller owns no
     * keys yet, so invalid ranges are rejected consistently regardless of data
     * presence. Shared with the admin usage queries (G4.1).
     */
    static void validateTimeRange(Instant from, Instant to) {
        Instant toResolved = to == null ? Instant.now() : to;
        Instant fromResolved = from == null ? toResolved.minus(MAX_WINDOW) : from;
        if (!fromResolved.isBefore(toResolved)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID", "from must be before to");
        }
        if (Duration.between(fromResolved, toResolved).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_TOO_WIDE",
                    "The queried window must be at most " + MAX_WINDOW.toDays() + " days");
        }
    }

    static UsageStatsRepository.GroupBy parseGroupBy(String value) {
        if (value == null || value.isBlank()) {
            return UsageStatsRepository.GroupBy.PROJECT;
        }
        try {
            return UsageStatsRepository.GroupBy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_BY_INVALID",
                    "groupBy must be one of PROJECT, VIRTUAL_KEY, CACHE_LEVEL, DAY, USER, TEAM, MODEL, MONTH, PRODUCT");
        }
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
