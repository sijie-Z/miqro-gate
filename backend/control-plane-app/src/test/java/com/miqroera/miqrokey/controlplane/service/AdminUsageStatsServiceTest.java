package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.HourlyUsageReport;
import com.miqroera.miqrokey.controlplane.dto.HourlyUsageRow;
import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.usage.AdjustedUsageRow;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import com.miqroera.miqrokey.domain.usage.UsageEvent;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageAggRow;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator.UsageSummary;
import org.junit.jupiter.api.DisplayName;
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
    private static final UUID TEAM_ID = UUID.randomUUID();
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
    @DisplayName("an absurd page number is rejected instead of overflowing the offset (#475)")
    void absurdPageNumberRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.records(TENANT, Instant.now().minus(1, ChronoUnit.HOURS),
                        Instant.now(), Long.MAX_VALUE, 50, null, null, null, null, null, null, null, null))
                .isInstanceOf(com.miqroera.miqrokey.controlplane.service.ApiException.class)
                .hasMessageContaining("page");
    }

    @Test
    void summaryPassesEveryOptionalDimensionAsFilter() {
        when(usageStatsRepository.aggregateUsage(eq(UsageStatsRepository.GroupBy.DAY), any())).thenReturn(List.of());
        when(usageStatsRepository.aggregateHits(eq(UsageStatsRepository.GroupBy.DAY), any())).thenReturn(List.of());

        service.summary(admin, "day", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now(), USER_ID, PROJECT_ID,
                KEY_ID, CREDENTIAL_ID, SUBSCRIPTION_ID, PRODUCT_ID, MODEL, TEAM_ID);

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
        assertThat(filter.teamId()).isEqualTo(TEAM_ID);
    }

    @Test
    void summaryWithoutFiltersScopesToTenantOnly() {
        when(usageStatsRepository.aggregateUsage(any(), any())).thenReturn(List.of());
        when(usageStatsRepository.aggregateHits(any(), any())).thenReturn(List.of());

        service.summary(admin, null, null, null, null, null, null, null, null, null, null, null);

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
    void summaryCostComesFromTheRowsFrozenPrices() {
        when(usageStatsRepository.aggregateUsage(any(), any())).thenReturn(List.of(new UsageAggRow("g", "G", PRODUCT_ID,
                MODEL, CacheLevel.UPSTREAM, 2, new TokenBucket(1_000L, 500L, null, null, null, null, 1_500L, null),
                new java.math.BigDecimal("1000"), new java.math.BigDecimal("1000"), java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, UsageStatsAggregator.PricingGap.NONE, UsageAggRow.Outcome.NONE)));
        when(usageStatsRepository.aggregateHits(any(), any())).thenReturn(List.of());

        UsageSummary summary = service.summary(admin, "project", null, null, null, null, null, null, null, null, null,
                null);

        assertThat(summary.groups()).hasSize(1);
        // input 1000 * 1.00/1e6 = 0.001; output 500 * 2.00/1e6 = 0.001
        assertThat(summary.groups().get(0).cost().upstreamPaid()).isEqualByComparingTo("0.002");
    }

    @Test
    void summaryRejectsUnknownGroupBy() {
        assertThatThrownBy(
                () -> service.summary(admin, "bogus", null, null, null, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GROUP_BY_INVALID"));
    }

    @Test
    void summaryRejectsWindowLongerThan93Days() {
        Instant from = Instant.now().minus(100, ChronoUnit.DAYS);
        assertThatThrownBy(
                () -> service.summary(admin, null, from, null, null, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_TOO_WIDE"));
    }

    @Test
    void summaryRejectsInvertedWindow() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> service.summary(admin, null, now, now.minus(1, ChronoUnit.HOURS), null, null, null,
                null, null, null, null, null)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_INVALID"));
    }

    @Test
    void recordsPassesFiltersAndPaginates() {
        UsageEvent event = new UsageEvent(UUID.randomUUID(), TENANT, "req-1", KEY_ID, PROJECT_ID, PRODUCT_ID,
                CREDENTIAL_ID, MODEL, CacheLevel.UPSTREAM, new TokenBucket(10L, 5L, 0L, 0L, null, null, null, null),
                100L, 200, null, true, false, "gw-1", Instant.now(), "203.0.113.7", null);
        when(usageStatsRepository.countRecords(any())).thenReturn(1L);
        when(usageStatsRepository.findRecords(any(), eq(0L), eq(50))).thenReturn(List.of(unadjusted(event)));

        UsageRecordPage page = service.records(admin, null, null, 1, 50, USER_ID, PROJECT_ID, KEY_ID, CREDENTIAL_ID,
                SUBSCRIPTION_ID, PRODUCT_ID, MODEL, "203.0.113.7", TEAM_ID);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).modelId()).isEqualTo(MODEL);
        assertThat(page.items().get(0).virtualKeyId()).isEqualTo(KEY_ID);
        // #605: the calling-party address surfaces on the view and in the filter.
        assertThat(page.items().get(0).clientIp()).isEqualTo("203.0.113.7");
        ArgumentCaptor<UsageStatsRepository.UsageFilter> captor = ArgumentCaptor
                .forClass(UsageStatsRepository.UsageFilter.class);
        verify(usageStatsRepository).findRecords(captor.capture(), eq(0L), eq(50));
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().modelId()).isEqualTo(MODEL);
        assertThat(captor.getValue().clientIp()).isEqualTo("203.0.113.7");
        assertThat(captor.getValue().teamId()).isEqualTo(TEAM_ID);
    }

    @Test
    void recordsRejectsPageBelowOne() {
        assertThatThrownBy(
                () -> service.records(admin, null, null, 0, 50, null, null, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PAGE_INVALID"));
    }

    @Test
    void recordsRejectsOversizedPage() {
        assertThatThrownBy(
                () -> service.records(admin, null, null, 1, 201, null, null, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SIZE_INVALID"));
    }

    // -------------------------------------------------------------------
    // Hourly report (#634)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("hourly resolves the natural-day window in the caller's timezone and maps rows")
    void hourlyComputesWindowAndMapsRows() {
        UUID dimensionId = UUID.randomUUID();
        Instant hourStart = Instant.parse("2026-09-15T06:00:00Z");
        when(usageStatsRepository.aggregateHourly(eq(UsageStatsRepository.HourlyDimension.USER), any(), eq(480)))
                .thenReturn(List.of(new UsageStatsRepository.HourlyUsageRow(hourStart, PROJECT_ID, "Project One",
                        dimensionId, "regular_user", 2L, 100L, 50L, 10L, 5L)));

        HourlyUsageReport report = service.hourly(admin, "2026-09-15", 1, "user", USER_ID, PROJECT_ID, 480, TEAM_ID);

        ArgumentCaptor<UsageStatsRepository.UsageFilter> captor = ArgumentCaptor
                .forClass(UsageStatsRepository.UsageFilter.class);
        verify(usageStatsRepository).aggregateHourly(eq(UsageStatsRepository.HourlyDimension.USER), captor.capture(),
                eq(480));
        UsageStatsRepository.UsageFilter filter = captor.getValue();
        assertThat(filter.tenantId()).isEqualTo(TENANT);
        assertThat(filter.userId()).isEqualTo(USER_ID);
        assertThat(filter.projectId()).isEqualTo(PROJECT_ID);
        assertThat(filter.teamId()).isEqualTo(TEAM_ID);
        // 2026-09-15 00:00+08:00 .. 2026-09-16 00:00+08:00
        assertThat(filter.from()).isEqualTo(Instant.parse("2026-09-14T16:00:00Z"));
        assertThat(filter.to()).isEqualTo(Instant.parse("2026-09-15T16:00:00Z"));

        assertThat(report.date()).isEqualTo("2026-09-15");
        assertThat(report.days()).isEqualTo(1);
        assertThat(report.dimension()).isEqualTo("USER");
        assertThat(report.tzOffsetMinutes()).isEqualTo(480);
        assertThat(report.rows()).hasSize(1);
        HourlyUsageRow row = report.rows().get(0);
        assertThat(row.hourStart()).isEqualTo(hourStart);
        assertThat(row.projectLabel()).isEqualTo("Project One");
        assertThat(row.dimensionId()).isEqualTo(dimensionId);
        assertThat(row.dimensionLabel()).isEqualTo("regular_user");
        assertThat(row.requests()).isEqualTo(2);
        assertThat(row.totalTokens()).isEqualTo(165L);
    }

    @Test
    @DisplayName("hourly spans multiple days and defaults nulls to UTC/NONE")
    void hourlyMultiDayDefaults() {
        when(usageStatsRepository.aggregateHourly(eq(UsageStatsRepository.HourlyDimension.NONE), any(), eq(0)))
                .thenReturn(List.of());

        HourlyUsageReport report = service.hourly(admin, "2026-09-15", 7, null, null, null, null, null);

        ArgumentCaptor<UsageStatsRepository.UsageFilter> captor = ArgumentCaptor
                .forClass(UsageStatsRepository.UsageFilter.class);
        verify(usageStatsRepository).aggregateHourly(eq(UsageStatsRepository.HourlyDimension.NONE), captor.capture(),
                eq(0));
        // 7 days ending 2026-09-15, UTC: starts 2026-09-09T00:00Z.
        assertThat(captor.getValue().from()).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"));
        assertThat(captor.getValue().to()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
        assertThat(report.days()).isEqualTo(7);
        assertThat(report.dimension()).isEqualTo("NONE");
        assertThat(report.rows()).isEmpty();
    }

    @Test
    void hourlyRejectsOutOfRangeParameters() {
        assertThatThrownBy(() -> service.hourly(admin, "2026-09-15", 8, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("DAYS_INVALID"));
        assertThatThrownBy(() -> service.hourly(admin, "2026-09-15", 0, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("DAYS_INVALID"));
        assertThatThrownBy(() -> service.hourly(admin, "2026-09-15", 1, "bogus", null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DIMENSION_INVALID"));
        assertThatThrownBy(() -> service.hourly(admin, "2026-13-40", 1, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("DATE_INVALID"));
        assertThatThrownBy(() -> service.hourly(admin, null, 1, null, null, null, 2000, null)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("TZ_OFFSET_INVALID"));
    }

    private static PriceSnapshot price(PriceTokenType type, BigDecimal unitPrice) {
        return new PriceSnapshot(UUID.randomUUID(), PRODUCT_ID, MODEL, type, "USD", unitPrice, Instant.now(), "TEST",
                null, Instant.now());
    }

    /**
     * An unadjusted row — net equals observed, which is what makes the existing
     * assertions in this class double as the "no adjustment, no change" regression
     * guard for the net wiring (#709).
     */
    private static AdjustedUsageRow unadjusted(UsageEvent e) {
        TokenBucket t = e.tokens();
        return new AdjustedUsageRow(e, t.inputTokens(), t.outputTokens(), t.cacheReadInputTokens(),
                t.cacheCreationInputTokens(), false, null, null);
    }

}
