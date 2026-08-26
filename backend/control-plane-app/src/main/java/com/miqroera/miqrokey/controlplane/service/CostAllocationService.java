package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.repository.CostAllocationRepository;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.usage.CostAllocation;
import com.miqroera.miqrokey.domain.usage.CostAllocationTargetType;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cost allocation per subscription period (G4.3, {@code cost_allocations} V10):
 * metered usage cost per project from local usage x price snapshots (PAYG),
 * plus the Plan fixed cost (subscription price prorated to the period)
 * distributed across projects weighted by their token share.
 * {@code algorithm_version} makes re-allocation idempotent and versioned.
 *
 * <p>
 * Prices are the latest effective snapshot at allocation time; per-event price
 * snapshots on usage rows are a deferred column (see database-schema.md), so
 * re-allocation after a price change rewrites history under the same algorithm
 * version. Currency comes from the subscription (fallback USD).
 * </p>
 */
@Service
public class CostAllocationService {

    private static final Logger LOG = LoggerFactory.getLogger(CostAllocationService.class);

    /** Current allocation algorithm; bump to force a distinct history line. */
    public static final String ALGORITHM_VERSION = "1";

    private static final Duration MAX_WINDOW = Duration.ofDays(93);
    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000);

    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final CostAllocationRepository allocationRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public CostAllocationService(UpstreamSubscriptionRepository subscriptionRepository,
            CostAllocationRepository allocationRepository, PriceSnapshotRepository priceSnapshotRepository,
            NamedParameterJdbcTemplate jdbc) {
        this.subscriptionRepository = subscriptionRepository;
        this.allocationRepository = allocationRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.jdbc = jdbc;
    }

    /**
     * Computes and persists the cost allocation for one subscription period
     * (idempotent per algorithm version), then returns the rows.
     */
    @Transactional
    public List<CostAllocation> allocate(UUID tenantId, UUID subscriptionId, Instant periodStart, Instant periodEnd) {
        UpstreamSubscription subscription = requireSubscription(tenantId, subscriptionId);
        validatePeriod(periodStart, periodEnd);

        List<ProjectAllocation> usage = usageByProject(subscription, periodStart, periodEnd);
        if (usage.isEmpty()) {
            return List.of();
        }
        BigDecimal fixedCost = fixedCostFor(subscription, periodStart, periodEnd);
        long totalTokens = usage.stream().mapToLong(ProjectAllocation::tokens).sum();
        String currency = subscription.currency() != null ? subscription.currency() : "USD";
        Instant now = Instant.now();

        List<CostAllocation> rows = new ArrayList<>(usage.size());
        for (ProjectAllocation project : usage) {
            BigDecimal fixedShare = fixedCost.multiply(BigDecimal.valueOf(project.tokens()))
                    .divide(BigDecimal.valueOf(totalTokens), 10, RoundingMode.HALF_UP);
            rows.add(allocationRepository
                    .upsert(new CostAllocation(UUID.randomUUID(), tenantId, subscriptionId, periodStart, periodEnd,
                            CostAllocationTargetType.PROJECT, project.projectId(), fixedShare, project.cost(),
                            project.tokens(), project.cost().add(fixedShare), currency, ALGORITHM_VERSION, now, now)));
        }
        LOG.info("Allocated {} cost rows for subscription {} period {}-{} (algorithm {})", rows.size(), subscriptionId,
                periodStart, periodEnd, ALGORITHM_VERSION);
        return rows;
    }

    /** Persisted allocation rows for one period (no recomputation). */
    public List<CostAllocation> byPeriod(UUID tenantId, UUID subscriptionId, Instant periodStart, Instant periodEnd) {
        requireSubscription(tenantId, subscriptionId);
        validatePeriod(periodStart, periodEnd);
        return allocationRepository.findByPeriod(tenantId, subscriptionId, periodStart, periodEnd);
    }

    // -------------------------------------------------------------------

    private UpstreamSubscription requireSubscription(UUID tenantId, UUID subscriptionId) {
        UpstreamSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                        "Subscription not found or not visible"));
        if (!subscription.tenantId().equals(tenantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                    "Subscription not found or not visible");
        }
        return subscription;
    }

    private static void validatePeriod(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID", "from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_TOO_WIDE",
                    "The allocation window must be at most " + MAX_WINDOW.toDays() + " days");
        }
    }

    /**
     * Usage per project: tokens (input + output) per (product, model). Only events
     * attributed to the subscription's credentials count. Metered cost is computed
     * per (product, model) row (prices differ per model), then accumulated per
     * project.
     */
    private List<ProjectAllocation> usageByProject(UpstreamSubscription subscription, Instant from, Instant to) {
        List<UsageRow> rows = new ArrayList<>();
        jdbc.query("""
                SELECT ue.project_id AS project_id, ue.provider_product_id AS product_id, ue.model_id AS model_id,
                       SUM(COALESCE(ue.input_tokens, ue.prompt_tokens)) AS input_tokens,
                       SUM(COALESCE(ue.output_tokens, ue.completion_tokens)) AS output_tokens
                FROM usage_event ue
                WHERE ue.tenant_id = :tenantId
                  AND ue.credential_id IN (SELECT id FROM upstream_credentials
                                           WHERE subscription_id = :subscriptionId)
                  AND ue.occurred_at >= :from AND ue.occurred_at < :to
                GROUP BY ue.project_id, ue.provider_product_id, ue.model_id
                """,
                new MapSqlParameterSource("tenantId", subscription.tenantId())
                        .addValue("subscriptionId", subscription.id()).addValue("from", Timestamp.from(from))
                        .addValue("to", Timestamp.from(to)),
                rs -> {
                    UUID projectId = (UUID) rs.getObject("project_id");
                    UUID productId = (UUID) rs.getObject("product_id");
                    String modelId = rs.getString("model_id");
                    long input = rs.getLong("input_tokens");
                    long output = rs.getLong("output_tokens");
                    rows.add(new UsageRow(projectId, productId, modelId, input, output));
                });
        Map<UUID, ProjectAllocation> byProject = new LinkedHashMap<>();
        for (UsageRow row : rows) {
            ProjectAllocation acc = byProject.computeIfAbsent(row.projectId(), ProjectAllocation::new);
            acc.addCost(usageCost(row));
            acc.addTokens(row.tokens());
        }
        return new ArrayList<>(byProject.values());
    }

    /**
     * Fixed cost for the period: the subscription price prorated by the share of
     * the subscription period the allocation window covers. PAYG subscriptions have
     * no fixed cost. Returns zero when the subscription price is unknown.
     */
    private static BigDecimal fixedCostFor(UpstreamSubscription subscription, Instant from, Instant to) {
        if (subscription.billingMode() == BillingMode.PAYG || subscription.subscriptionPrice() == null
                || subscription.periodStart() == null || subscription.periodEnd() == null) {
            return BigDecimal.ZERO;
        }
        long subDays = Math.max(1, Duration.between(subscription.periodStart(), subscription.periodEnd()).toDays());
        long windowDays = Duration.between(from, to).toDays();
        return subscription.subscriptionPrice().multiply(BigDecimal.valueOf(windowDays))
                .divide(BigDecimal.valueOf(subDays), 10, RoundingMode.HALF_UP);
    }

    /** Metered cost of one project's usage at the latest price snapshots. */
    private BigDecimal usageCost(UsageRow row) {
        BigDecimal inputPrice = price(row.productId(), row.modelId(), PriceTokenType.INPUT);
        BigDecimal outputPrice = price(row.productId(), row.modelId(), PriceTokenType.OUTPUT);
        BigDecimal inputCost = inputPrice.multiply(BigDecimal.valueOf(row.input())).divide(PER_MILLION, 10,
                RoundingMode.HALF_UP);
        BigDecimal outputCost = outputPrice.multiply(BigDecimal.valueOf(row.output())).divide(PER_MILLION, 10,
                RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    /**
     * Latest price at now; zero when no snapshot exists (no-price models cost 0).
     */
    private BigDecimal price(UUID productId, String modelId, PriceTokenType type) {
        if (productId == null || modelId == null) {
            return BigDecimal.ZERO;
        }
        return priceSnapshotRepository.findLatestAt(productId, modelId, type, Instant.now())
                .map(PriceSnapshot::unitPrice).orElse(BigDecimal.ZERO);
    }

    private record UsageRow(UUID projectId, UUID productId, String modelId, long input, long output) {

        long tokens() {
            return input + output;
        }
    }

    /** Accumulated per-project metered cost and weighting tokens. */
    private static final class ProjectAllocation {

        private final UUID projectId;
        private BigDecimal cost = BigDecimal.ZERO;
        private long tokens;

        ProjectAllocation(UUID projectId) {
            this.projectId = projectId;
        }

        UUID projectId() {
            return projectId;
        }

        BigDecimal cost() {
            return cost;
        }

        long tokens() {
            return tokens;
        }

        void addCost(BigDecimal amount) {
            cost = cost.add(amount);
        }

        void addTokens(long amount) {
            tokens += amount;
        }
    }
}
