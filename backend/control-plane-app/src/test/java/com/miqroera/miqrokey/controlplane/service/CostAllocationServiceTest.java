package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.BillingMode;
import com.miqroera.miqrokey.domain.model.PlanScope;
import com.miqroera.miqrokey.domain.model.SubscriptionStatus;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.repository.CostAllocationRepository;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.usage.CostAllocation;
import com.miqroera.miqrokey.domain.usage.CostAllocationTargetType;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CostAllocationService} (G4.3): per-model metered cost
 * from price snapshots, Plan fixed-cost proration and token-weighted
 * distribution, empty-usage behavior, and tenant scoping.
 */
@ExtendWith(MockitoExtension.class)
class CostAllocationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();
    private static final String MODEL = "model-a";

    @Mock
    private UpstreamSubscriptionRepository subscriptionRepository;
    @Mock
    private CostAllocationRepository allocationRepository;
    @Mock
    private PriceSnapshotRepository priceSnapshotRepository;
    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private CostAllocationService service;
    private final List<CostAllocation> stored = new ArrayList<>();
    private Instant from;
    private Instant to;

    @BeforeEach
    void setUp() {
        service = new CostAllocationService(subscriptionRepository, allocationRepository, priceSnapshotRepository,
                jdbc);
        lenient().when(allocationRepository.upsert(any())).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        from = Instant.parse("2026-08-01T00:00:00Z");
        to = Instant.parse("2026-08-31T00:00:00Z");
    }

    @Test
    void allocateComputesMeteredCostPerModelAndFixedShareByTokenWeight() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        // Project A: 1000 input + 500 output on model-a; Project B: 500 input.
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(row(PROJECT_A, PRODUCT_ID, MODEL, 1_000L, 500L));
            handler.processRow(row(PROJECT_B, PRODUCT_ID, MODEL, 500L, 0L));
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        when(priceSnapshotRepository.findLatestAt(eq(PRODUCT_ID), eq(MODEL), eq(PriceTokenType.INPUT), any()))
                .thenReturn(Optional.of(price(new BigDecimal("1.00"))));
        when(priceSnapshotRepository.findLatestAt(eq(PRODUCT_ID), eq(MODEL), eq(PriceTokenType.OUTPUT), any()))
                .thenReturn(Optional.of(price(new BigDecimal("2.00"))));

        List<CostAllocation> rows = service.allocate(TENANT, SUBSCRIPTION_ID, from, to);

        assertThat(rows).hasSize(2);
        CostAllocation a = rows.stream().filter(r -> r.targetId().equals(PROJECT_A)).findFirst().orElseThrow();
        CostAllocation b = rows.stream().filter(r -> r.targetId().equals(PROJECT_B)).findFirst().orElseThrow();
        // A: input 1000 * 1.00/1e6 = 0.001 + output 500 * 2.00/1e6 = 0.001 = 0.002
        assertThat(a.usageCost()).isEqualByComparingTo("0.002");
        assertThat(a.weightTokens()).isEqualTo(1_500L);
        // B: input 500 * 1.00/1e6 = 0.0005
        assertThat(b.usageCost()).isEqualByComparingTo("0.0005");
        // Fixed cost 100 prorated to the 30-day window of a 30-day period = 100,
        // split by token weight 1500:500 = 3:1.
        assertThat(a.fixedCost()).isEqualByComparingTo("75.00");
        assertThat(b.fixedCost()).isEqualByComparingTo("25.00");
        assertThat(a.allocatedAmount()).isEqualByComparingTo("75.002");
        assertThat(a.algorithmVersion()).isEqualTo(CostAllocationService.ALGORITHM_VERSION);
        assertThat(a.targetType()).isEqualTo(CostAllocationTargetType.PROJECT);
    }

    @Test
    void allocateReturnsEmptyWhenNoUsageAndWritesNothing() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        // Void query: default behavior is no rows.

        assertThat(service.allocate(TENANT, SUBSCRIPTION_ID, from, to)).isEmpty();
        verify(allocationRepository, org.mockito.Mockito.never()).upsert(any());
    }

    @Test
    void allocateProratesFixedCostWhenWindowShorterThanSubscriptionPeriod() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        doAnswer(inv -> {
            inv.getArgument(2, RowCallbackHandler.class).processRow(row(PROJECT_A, PRODUCT_ID, MODEL, 100L, 0L));
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        when(priceSnapshotRepository.findLatestAt(eq(PRODUCT_ID), eq(MODEL), eq(PriceTokenType.INPUT), any()))
                .thenReturn(Optional.of(price(new BigDecimal("1.00"))));
        when(priceSnapshotRepository.findLatestAt(eq(PRODUCT_ID), eq(MODEL), eq(PriceTokenType.OUTPUT), any()))
                .thenReturn(Optional.empty());
        // 15-day window of a 30-day subscription period -> half the price.
        Instant halfFrom = Instant.parse("2026-08-01T00:00:00Z");
        Instant halfTo = Instant.parse("2026-08-16T00:00:00Z");

        List<CostAllocation> rows = service.allocate(TENANT, SUBSCRIPTION_ID, halfFrom, halfTo);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).fixedCost()).isEqualByComparingTo("50.00");
    }

    @Test
    void otherTenantCannotSeeTheSubscription() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));
        UUID otherTenant = UUID.randomUUID();

        assertThatThrownBy(() -> service.byPeriod(otherTenant, SUBSCRIPTION_ID, from, to)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SUBSCRIPTION_NOT_FOUND"));
    }

    @Test
    void invalidPeriodsAreRejected() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));

        assertThatThrownBy(() -> service.byPeriod(TENANT, SUBSCRIPTION_ID, to, from)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_INVALID"));
        assertThatThrownBy(
                () -> service.byPeriod(TENANT, SUBSCRIPTION_ID, from, from.plus(java.time.Duration.ofDays(100))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_TOO_WIDE"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private UpstreamSubscription subscription() {
        return new UpstreamSubscription(SUBSCRIPTION_ID, TENANT, PRODUCT_ID, "Sub", null,
                BillingMode.FIXED_SUBSCRIPTION, PlanScope.NONE, new BigDecimal("100.00"), "USD",
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"), null, null, null,
                SubscriptionStatus.ACTIVE, null, com.miqroera.miqrokey.domain.model.StatusSource.MANUAL_UNKNOWN, 0,
                Instant.now(), Instant.now());
    }

    private static ResultSet row(UUID projectId, UUID productId, String modelId, long input, long output)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("project_id")).thenReturn(projectId);
        when(rs.getObject("product_id")).thenReturn(productId);
        when(rs.getString("model_id")).thenReturn(modelId);
        when(rs.getLong("input_tokens")).thenReturn(input);
        when(rs.getLong("output_tokens")).thenReturn(output);
        return rs;
    }

    private static PriceSnapshot price(BigDecimal unitPrice) {
        return new PriceSnapshot(UUID.randomUUID(), PRODUCT_ID, MODEL, PriceTokenType.INPUT, "USD", unitPrice,
                Instant.parse("2026-01-01T00:00:00Z"), "TEST", null, Instant.now());
    }
}
