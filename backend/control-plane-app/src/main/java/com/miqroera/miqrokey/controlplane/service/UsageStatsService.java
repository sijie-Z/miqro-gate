package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.HitAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final PriceSnapshotRepository priceSnapshotRepository;

    public UsageStatsService(VirtualKeyRepository keyRepository, UsageStatsRepository usageStatsRepository,
            PriceSnapshotRepository priceSnapshotRepository) {
        this.keyRepository = keyRepository;
        this.usageStatsRepository = usageStatsRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
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
        UsageStatsRepository.GroupBy dimension = parseGroupBy(groupBy);
        Set<UUID> keyIds = ownKeyIds(user);
        if (keyIds.isEmpty()) {
            // No keys to aggregate — return a zeroed summary without touching the
            // usage tables.
            return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), List.of(), List.of(),
                    new LinkedHashMap<>());
        }
        UsageStatsRepository.UsageFilter filter = filter(user, keyIds, from, to);

        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (PriceSnapshot p : priceSnapshotRepository.findAllLatestAt(Instant.now())) {
            prices.put(p.providerProductId() + ":" + p.modelId() + ":" + p.tokenType().name(), p.unitPrice());
        }
        List<UsageAggRow> usageRows = usageStatsRepository.aggregateUsage(dimension, filter);
        List<HitAggRow> hitRows = usageStatsRepository.aggregateHits(dimension, filter);
        return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), usageRows, hitRows, prices);
    }

    /** Paged raw usage records for the caller's own keys, newest first. */
    public UsageRecordPage records(User user, Instant from, Instant to, long page, int size) {
        if (page < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIZE_INVALID",
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Set<UUID> keyIds = ownKeyIds(user);
        if (keyIds.isEmpty()) {
            return new UsageRecordPage(List.of(), page, size, 0L);
        }
        UsageStatsRepository.UsageFilter filter = filter(user, keyIds, from, to);

        long total = usageStatsRepository.countRecords(filter);
        List<UsageEvent> events = usageStatsRepository.findRecords(filter, (page - 1) * size, size);
        List<UsageRecordPage.UsageRecordView> items = new ArrayList<>(events.size());
        for (UsageEvent e : events) {
            items.add(view(e));
        }
        return new UsageRecordPage(items, page, size, total);
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
        Instant toResolved = to == null ? Instant.now() : to;
        Instant fromResolved = from == null ? toResolved.minus(MAX_WINDOW) : from;
        if (!fromResolved.isBefore(toResolved)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID", "from must be before to");
        }
        if (Duration.between(fromResolved, toResolved).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_TOO_WIDE",
                    "The queried window must be at most " + MAX_WINDOW.toDays() + " days");
        }
        return new UsageStatsRepository.UsageFilter(user.tenantId(), keyIds, fromResolved, toResolved);
    }

    private static UsageStatsRepository.GroupBy parseGroupBy(String value) {
        if (value == null || value.isBlank()) {
            return UsageStatsRepository.GroupBy.PROJECT;
        }
        try {
            return UsageStatsRepository.GroupBy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_BY_INVALID",
                    "groupBy must be one of PROJECT, VIRTUAL_KEY, CACHE_LEVEL, DAY");
        }
    }

    private static UsageRecordPage.UsageRecordView view(UsageEvent e) {
        TokenBucket t = e.tokens();
        Long input = orNull(t != null ? t.inputTokens() : null, t != null ? t.promptTokens() : null);
        Long output = orNull(t != null ? t.outputTokens() : null, t != null ? t.completionTokens() : null);
        return new UsageRecordPage.UsageRecordView(e.occurredAt(), e.modelId(), e.cacheLevel(), input, output,
                t != null ? t.cacheReadInputTokens() : null, t != null ? t.cacheCreationInputTokens() : null,
                t != null ? t.totalTokens() : null, e.latencyMs(), e.upstreamStatusCode(), e.providerRequestId(),
                e.gatewayRequestId(), e.isComplete(), e.usageMissing(), e.virtualKeyId());
    }

    /** Primary input/output token, preferring the protocol-specific column. */
    private static Long orNull(Long primary, Long fallback) {
        return primary != null ? primary : fallback;
    }
}
