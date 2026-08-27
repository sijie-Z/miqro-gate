package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final UsageStatsRepository usageStatsRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;

    public AdminUsageStatsService(UsageStatsRepository usageStatsRepository,
            PriceSnapshotRepository priceSnapshotRepository) {
        this.usageStatsRepository = usageStatsRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
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
    public UsageSummary summary(User admin, String groupBy, Instant from, Instant to, UUID userId, UUID projectId,
            UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId, String modelId) {
        UsageStatsRepository.GroupBy dimension = UsageStatsService.parseGroupBy(groupBy);
        UsageStatsService.validateTimeRange(from, to);
        UsageStatsRepository.UsageFilter filter = adminFilter(admin, from, to, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId);

        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (PriceSnapshot p : priceSnapshotRepository.findAllLatestAt(Instant.now())) {
            prices.put(p.providerProductId() + ":" + p.modelId() + ":" + p.tokenType().name(), p.unitPrice());
        }
        List<UsageAggRow> usageRows = usageStatsRepository.aggregateUsage(dimension, filter);
        List<HitAggRow> hitRows = usageStatsRepository.aggregateHits(dimension, filter);
        return UsageStatsAggregator.aggregate(dimension.name().toLowerCase(), usageRows, hitRows, prices);
    }

    /** Paged raw usage records over the whole tenant, newest first. */
    public UsageRecordPage records(User admin, Instant from, Instant to, long page, int size, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId) {
        if (page < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIZE_INVALID",
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        UsageStatsService.validateTimeRange(from, to);
        UsageStatsRepository.UsageFilter filter = adminFilter(admin, from, to, userId, projectId, virtualKeyId,
                credentialId, subscriptionId, providerProductId, modelId);

        long total = usageStatsRepository.countRecords(filter);
        List<UsageEvent> events = usageStatsRepository.findRecords(filter, (page - 1) * size, size);
        List<UsageRecordPage.UsageRecordView> items = new ArrayList<>(events.size());
        for (UsageEvent e : events) {
            items.add(view(e));
        }
        return new UsageRecordPage(items, page, size, total);
    }

    // -------------------------------------------------------------------

    /**
     * Admin scope: no key-set restriction (key set is {@code null}); every
     * dimension is an optional filter. Tenant scoping still comes from the
     * authenticated admin — there is no cross-tenant query shape.
     */
    private static UsageStatsRepository.UsageFilter adminFilter(User admin, Instant from, Instant to, UUID userId,
            UUID projectId, UUID virtualKeyId, UUID credentialId, UUID subscriptionId, UUID providerProductId,
            String modelId) {
        UsageStatsService.validateTimeRange(from, to);
        Instant toResolved = to == null ? Instant.now() : to;
        Instant fromResolved = from == null ? toResolved.minus(UsageStatsService.MAX_WINDOW) : from;
        return new UsageStatsRepository.UsageFilter(admin.tenantId(),
                virtualKeyId != null ? Set.of(virtualKeyId) : null, userId, projectId, credentialId, subscriptionId,
                providerProductId, modelId != null && !modelId.isBlank() ? modelId : null, fromResolved, toResolved);
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
