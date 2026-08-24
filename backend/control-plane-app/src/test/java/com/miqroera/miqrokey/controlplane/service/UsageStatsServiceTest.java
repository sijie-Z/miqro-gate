package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsageRecordPage;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UsageStatsService}: cost math wired to the real
 * {@code UsageStatsAggregator}, caller-scoped filtering, pagination rules, and
 * the maximum time window.
 */
@ExtendWith(MockitoExtension.class)
class UsageStatsServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID KEY_A = UUID.randomUUID();
    private static final UUID KEY_B = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final String MODEL = "model-a";

    @Mock
    private VirtualKeyRepository keyRepository;
    @Mock
    private UsageStatsRepository usageStatsRepository;
    @Mock
    private PriceSnapshotRepository priceSnapshotRepository;

    private UsageStatsService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new UsageStatsService(keyRepository, usageStatsRepository, priceSnapshotRepository);
        user = new User(USER_ID, TENANT, "u", "U", new byte[32], UserRole.USER, UserStatus.ACTIVE, false, 0, null, null,
                0L, Instant.now(), Instant.now());
    }

    @Test
    void summaryComputesCostFromPriceSnapshot() {
        when(keyRepository.findAllByUserId(USER_ID)).thenReturn(List.of(key(KEY_A), key(KEY_B)));
        when(priceSnapshotRepository.findAllLatestAt(any(Instant.class)))
                .thenReturn(List.of(price(PriceTokenType.INPUT, new BigDecimal("1.00")),
                        price(PriceTokenType.OUTPUT, new BigDecimal("2.00"))));
        when(usageStatsRepository.aggregateUsage(eq(UsageStatsRepository.GroupBy.VIRTUAL_KEY), any()))
                .thenReturn(List.of(new UsageAggRow("key-" + KEY_A, "k-a", PRODUCT, MODEL, CacheLevel.UPSTREAM, 2L,
                        new TokenBucket(1_000L, 500L, null, null, null, null, 1_500L, null))));
        when(usageStatsRepository.aggregateHits(any(), any())).thenReturn(List.of());

        UsageSummary summary = service.summary(user, "virtual_key", null, null);

        assertThat(summary.groupBy()).isEqualTo("virtual_key");
        assertThat(summary.groups()).hasSize(1);
        // input 1000 * 1.00/1e6 = 0.001; output 500 * 2.00/1e6 = 0.001
        assertThat(summary.groups().get(0).requests().upstream()).isEqualTo(2L);
        assertThat(summary.groups().get(0).tokens().input()).isEqualTo(1_000L);
        assertThat(summary.groups().get(0).tokens().output()).isEqualTo(500L);
        assertThat(summary.groups().get(0).cost().upstreamPaid()).isEqualByComparingTo("0.002");
        assertThat(summary.groups().get(0).cost().gatewayObserved()).isEqualByComparingTo("0.002");
        // totals mirror the single group
        assertThat(summary.totals().cost().upstreamPaid()).isEqualByComparingTo("0.002");
    }

    @Test
    void summaryWithNoOwnedKeysReturnsEmptySummaryWithoutHittingUsageRepo() {
        when(keyRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        UsageSummary summary = service.summary(user, "project", null, null);

        assertThat(summary.groups()).isEmpty();
        assertThat(summary.totals().requests().total()).isZero();
        assertThat(summary.totals().cost().upstreamPaid()).isEqualByComparingTo("0");
        verify(usageStatsRepository, never()).aggregateUsage(any(), any());
    }

    @Test
    void summaryDefaultsGroupByToProject() {
        when(keyRepository.findAllByUserId(USER_ID)).thenReturn(List.of(key(KEY_A)));
        when(priceSnapshotRepository.findAllLatestAt(any(Instant.class))).thenReturn(List.of());
        when(usageStatsRepository.aggregateUsage(eq(UsageStatsRepository.GroupBy.PROJECT), any()))
                .thenReturn(List.of());
        when(usageStatsRepository.aggregateHits(any(), any())).thenReturn(List.of());

        UsageSummary summary = service.summary(user, null, null, null);

        assertThat(summary.groupBy()).isEqualTo("project");
    }

    @Test
    void summaryRejectsUnknownGroupBy() {
        assertThatThrownBy(() -> service.summary(user, "bogus", null, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("GROUP_BY_INVALID"));
    }

    @Test
    void summaryRejectsWindowLongerThan93Days() {
        when(keyRepository.findAllByUserId(USER_ID)).thenReturn(List.of(key(KEY_A)));
        Instant from = Instant.now().minus(94, ChronoUnit.DAYS);
        Instant to = Instant.now();

        assertThatThrownBy(() -> service.summary(user, null, from, to)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_TOO_WIDE"));
    }

    @Test
    void summaryRejectsInvertedWindow() {
        when(keyRepository.findAllByUserId(USER_ID)).thenReturn(List.of(key(KEY_A)));
        Instant now = Instant.now();

        assertThatThrownBy(() -> service.summary(user, null, now, now.minusSeconds(1))).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCode()).isEqualTo("TIME_RANGE_INVALID"));
    }

    // ------------------------------------------------------------------
    // records
    // ------------------------------------------------------------------

    @Test
    void recordsPaginatesOverOwnedKeysOnly() {
        when(keyRepository.findAllByUserId(USER_ID)).thenReturn(List.of(key(KEY_A)));
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        when(usageStatsRepository.countRecords(any())).thenReturn(1L);
        when(usageStatsRepository.findRecords(any(), eq(0L), eq(50))).thenReturn(List.of(event()));

        UsageRecordPage page = service.records(user, from, to, 1, 50);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.page()).isEqualTo(1L);
        assertThat(page.size()).isEqualTo(50L);
        assertThat(page.items()).hasSize(1);
        UsageRecordPage.UsageRecordView view = page.items().get(0);
        assertThat(view.virtualKeyId()).isEqualTo(KEY_A);
        assertThat(view.inputTokens()).isEqualTo(1_000L);
        assertThat(view.outputTokens()).isEqualTo(500L);
        assertThat(view.cacheReadInputTokens()).isEqualTo(200L);
        assertThat(view.totalTokens()).isEqualTo(1_700L);
        assertThat(view.modelId()).isEqualTo(MODEL);
        assertThat(view.cacheLevel()).isEqualTo(CacheLevel.UPSTREAM);
        assertThat(view.isComplete()).isTrue();
        assertThat(view.usageMissing()).isFalse();

        // The filter carries the caller's own key set and tenant.
        var captor = org.mockito.ArgumentCaptor.forClass(UsageStatsRepository.UsageFilter.class);
        verify(usageStatsRepository).countRecords(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().virtualKeyIds()).containsExactly(KEY_A);
    }

    @Test
    void recordsRejectsPageBelowOne() {
        assertThatThrownBy(() -> service.records(user, null, null, 0, 50)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PAGE_INVALID"));
    }

    @Test
    void recordsRejectsOversizedPage() {
        assertThatThrownBy(() -> service.records(user, null, null, 1, 201)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("SIZE_INVALID"));
    }

    @Test
    void recordsScalesOffsetWithPage() {
        when(keyRepository.findAllByUserId(USER_ID)).thenReturn(List.of(key(KEY_A)));
        when(usageStatsRepository.countRecords(any())).thenReturn(0L);

        service.records(user, null, null, 3, 25);

        verify(usageStatsRepository).findRecords(any(), eq(50L), eq(25));
    }

    // ------------------------------------------------------------------

    private static VirtualKey key(UUID id) {
        return new VirtualKey(id, TENANT, "pk", new byte[32], "pre", "0000", USER_ID, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), VirtualKeyPurpose.CLAUDE_CODE, "k", "DISABLED",
                VirtualKeyStatus.ACTIVE, Instant.now(), null, null, null, 0L);
    }

    private static PriceSnapshot price(PriceTokenType type, BigDecimal unitPrice) {
        return new PriceSnapshot(UUID.randomUUID(), PRODUCT, MODEL, type, "USD", unitPrice, Instant.now(), "TEST",
                UUID.randomUUID(), Instant.now());
    }

    private static UsageEvent event() {
        return new UsageEvent(UUID.randomUUID(), TENANT, "chatcmpl-123", KEY_A, UUID.randomUUID(), PRODUCT,
                UUID.randomUUID(), MODEL, CacheLevel.UPSTREAM,
                new TokenBucket(1_000L, 500L, null, 200L, null, null, 1_700L, null), 42L, 200, new byte[16], true,
                false, "greq", Instant.now());
    }
}
