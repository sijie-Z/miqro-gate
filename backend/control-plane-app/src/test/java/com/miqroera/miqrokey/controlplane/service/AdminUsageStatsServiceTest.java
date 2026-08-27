package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminUsageStatsService} (G4.1): admin-scoped filters
 * (time/user/project/key/credential/subscription/vendor/model), no key-set
 * restriction, and the same window/pagination rules as the self-service path.
 */
@ExtendWith(MockitoExtension.class)
class AdminUsageStatsServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String MODEL = "model-a";

    @Mock
    private UsageStatsRepository usageStatsRepository;
    @Mock
    private PriceSnapshotRepository priceSnapshotRepository;

    private AdminUsageStatsService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new AdminUsageStatsService(usageStatsRepository, priceSnapshotRepository);
        admin = new User(ADMIN_ID, TENANT, "root", "Root Admin", new byte[32], UserRole.SYSTEM_ADMIN, UserStatus.ACTIVE,
                false, 0, null, null, 0L, Instant.now(), Instant.now());
    }

    @Test
    void summaryPassesEveryOptionalDimensionAsFilter() {
        when(priceSnapshotRepository.findAllLatestAt(any(Instant.class))).thenReturn(List.of());
        when(usageStatsRepository.aggregateUsage(eq(UsageStatsRepository.GroupBy.DAY), any())).thenReturn(List.of());
        when(usageStatsRepository.aggregateHits(eq(UsageStatsRepository.GroupBy.DAY), any())).thenReturn(List.of());

        service.summary(admin, "day", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now(), USER_ID, PROJECT_ID,
                KEY_ID, CREDENTIAL_ID, SUBSCRIPTION_ID, PRODUCT_ID, MODEL);

        ArgumentCaptor<UsageStatsRepository.UsageFilter> captor = ArgumentCaptor
                .forClass(UsageStatsRepository.UsageFilter.class);
        verify(usageStatsRepository).aggregateUsage(eq(UsageStatsRepository.GroupBy.DAY), captor.capture());
        UsageStatsRepository.UsageFilter filter = captor.getValue();
        assertThat(filter.tenantId()).isEqualTo(TENANT);
        // Admin scope: no caller-scoped key set.
        assertThat(filter.virtualKeyIds()).containsExactly(KEY_ID);
        assertThat(filter.userId()).isEqualTo(USER_ID);
        assertThat(filter.projectId()).isEqualTo(PROJECT_ID);
        assertThat(filter.credentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(filter.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(filter.providerProductId()).isEqualTo(PRODUCT_ID);
        assertThat(filter.modelId()).isEqualTo(MODEL);
    }

    @Test
    void summaryWithoutFiltersScopesToTenantOnly() {
        when(priceSnapshotRepository.findAllLatestAt(any(Instant.class))).thenReturn(List.of());
        when(usageStatsRepository.aggregateUsage(any(), any())).thenReturn(List.of());
        when(usageStatsRepository.aggregateHits(any(), any())).thenReturn(List.of());

        service.summary(admin, null, null, null, null, null, null, null, null, null, null);

        ArgumentCaptor<UsageStatsRepository.UsageFilter> captor = ArgumentCaptor
                .forClass(UsageStatsRepository.UsageFilter.class);
        verify(usageStatsRepository).aggregateUsage(any(), captor.capture());
        UsageStatsRepository.UsageFilter filter = captor.getValue();
        assertThat(filter.tenantId()).isEqualTo(TENANT);
        assertThat(filter.virtualKeyIds()).isNull();
        assertThat(filter.userId()).isNull();
        assertThat(filter.projectId()).isNull();
        assertThat(filter.credentialId()).isNull();
        assertThat(filter.subscriptionId()).isNull();
        assertThat(filter.providerProductId()).isNull();
        assertThat(filter.modelId()).isNull();
        // Default window = last 93 days ending now.
        assertThat(filter.to()).isNotNull();
        assertThat(filter.from()).isEqualTo(filter.to().minus(UsageStatsService.MAX_WINDOW));
    }

    @Test
    void summaryComputesCostFromPriceSnapshot() {
        when(priceSnapshotRepository.findAllLatestAt(any(Instant.class)))
                .thenReturn(List.of(price(PriceTokenType.INPUT, new BigDecimal("1.00")),
                        price(PriceTokenType.OUTPUT, new BigDecimal("2.00"))));
        when(usageStatsRepository.aggregateUsage(any(), any())).thenReturn(List.of(new UsageAggRow("g", "G", PRODUCT_ID,
                MODEL, CacheLevel.UPSTREAM, 2, new TokenBucket(1_000L, 500L, null, null, null, null, 1_500L, null))));
        when(usageStatsRepository.aggregateHits(any(), any())).thenReturn(List.of());

        UsageSummary summary = service.summary(admin, "project", null, null, null, null, null, null, null, null, null);

        assertThat(summary.groups()).hasSize(1);
        // input 1000 * 1.00/1e6 = 0.001; output 500 * 2.00/1e6 = 0.001
        assertThat(summary.groups().get(0).cost().upstreamPaid()).isEqualByComparingTo("0.002");
    }

    @Test
    void summaryRejectsUnknownGroupBy() {
        assertThatThrownBy(() -> service.summary(admin, "bogus", null, null, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GROUP_BY_INVALID"));
    }

    @Test
    void summaryRejectsWindowLongerThan93Days() {
        Instant from = Instant.now().minus(100, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.summary(admin, null, from, null, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_TOO_WIDE"));
    }

    @Test
    void summaryRejectsInvertedWindow() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> service.summary(admin, null, now, now.minus(1, ChronoUnit.HOURS), null, null, null,
                null, null, null, null)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_INVALID"));
    }

    @Test
    void recordsPassesFiltersAndPaginates() {
        UsageEvent event = new UsageEvent(UUID.randomUUID(), TENANT, "req-1", KEY_ID, PROJECT_ID, PRODUCT_ID,
                CREDENTIAL_ID, MODEL, CacheLevel.UPSTREAM, new TokenBucket(10L, 5L, 0L, 0L, null, null, null, null),
                100L, 200, null, true, false, "gw-1", Instant.now());
        when(usageStatsRepository.countRecords(any())).thenReturn(1L);
        when(usageStatsRepository.findRecords(any(), eq(0L), eq(50))).thenReturn(List.of(event));

        UsageRecordPage page = service.records(admin, null, null, 1, 50, USER_ID, PROJECT_ID, KEY_ID, CREDENTIAL_ID,
                SUBSCRIPTION_ID, PRODUCT_ID, MODEL);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).modelId()).isEqualTo(MODEL);
        assertThat(page.items().get(0).virtualKeyId()).isEqualTo(KEY_ID);
        ArgumentCaptor<UsageStatsRepository.UsageFilter> captor = ArgumentCaptor
                .forClass(UsageStatsRepository.UsageFilter.class);
        verify(usageStatsRepository).findRecords(captor.capture(), eq(0L), eq(50));
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().modelId()).isEqualTo(MODEL);
    }

    @Test
    void recordsRejectsPageBelowOne() {
        assertThatThrownBy(() -> service.records(admin, null, null, 0, 50, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PAGE_INVALID"));
    }

    @Test
    void recordsRejectsOversizedPage() {
        assertThatThrownBy(() -> service.records(admin, null, null, 1, 201, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SIZE_INVALID"));
    }

    private static PriceSnapshot price(PriceTokenType type, BigDecimal unitPrice) {
        return new PriceSnapshot(UUID.randomUUID(), PRODUCT_ID, MODEL, type, "USD", unitPrice, Instant.now(), "TEST",
                null, Instant.now());
    }
}
