package com.miqroera.miqrokey.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miqroera.miqrokey.controlplane.dto.UsageAdjustmentRequest;
import com.miqroera.miqrokey.controlplane.dto.UsageAdjustmentView;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UsageAdjustmentRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.AdjustmentTarget;
import com.miqroera.miqrokey.domain.usage.AdjustmentType;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.UsageAdjustment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * Usage-adjustment business rules (#709): the ledger is append-only, so what
 * matters here is what gets <em>rejected</em> — an empty correction, one that
 * would drive a dimension negative, a reversal of a reversal — and that a
 * retried submission does not book the same correction twice.
 */
@DisplayName("Usage adjustment service")
class UsageAdjustmentServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();
    private static final String REQUEST_ID = "af453dd9-7267-42e3-ab5d-a105a12ce8d4";
    private static final Instant NOW = Instant.parse("2026-09-17T02:00:00Z");

    private final UsageAdjustmentRepository repository = mock(UsageAdjustmentRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UsageAdjustmentService service = new UsageAdjustmentService(repository, auditService);

    @BeforeEach
    void resolveRequestId() {
        when(repository.findUsageEventIdByGatewayRequestId(TENANT, REQUEST_ID)).thenReturn(Optional.of(EVENT));
        when(repository.append(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a correction is appended against the resolved usage event and audited")
    void correctionIsAppended() {
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, 0L, 0L)));

        UsageAdjustmentView view = service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, -200L, null, null, "上游账单修正", "BILL_FIX", null, null),
                audit());

        assertThat(view.usageEventId()).isEqualTo(EVENT);
        assertThat(view.adjustmentType()).isEqualTo("USAGE");
        assertThat(view.outputTokensDelta()).isEqualTo(-200L);
        assertThat(view.createdAt()).isNotNull();

        verify(repository).lockUsageEvent(EVENT);
        verify(auditService).record(eq(TENANT), any(), eq("USAGE_ADJUSTMENT_CREATED"), eq("USAGE_ADJUSTMENT"), any(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("an adjustment with no delta at all is rejected")
    void emptyAdjustmentIsRejected() {
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, 0L, 0L)));

        assertThatThrownBy(() -> service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, null, null, null, "空调整", null, null, null), audit()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ADJUSTMENT_EMPTY"));
        verify(repository, never()).append(any());
    }

    @Test
    @DisplayName("an adjustment that is all zeroes is rejected rather than recorded as a no-op")
    void zeroOnlyAdjustmentIsRejected() {
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, 0L, 0L)));

        assertThatThrownBy(() -> service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, 0L, 0L, null, null, "全零", null, null, null), audit()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ADJUSTMENT_EMPTY"));
        verify(repository, never()).append(any());
    }

    @Test
    @DisplayName("a correction that would drive a dimension below zero is refused")
    void negativeNetIsRefused() {
        // observed 500 output, already corrected by -400 → net 100; another -200
        // would leave -100.
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, 0L, -400L)));

        assertThatThrownBy(() -> service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, -200L, null, null, "过度修正", null, null, null), audit()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ADJUSTMENT_WOULD_GO_NEGATIVE"));
        verify(repository, never()).append(any());
    }

    @Test
    @DisplayName("a correction may consume a dimension exactly down to zero")
    void netReachingZeroIsAllowed() {
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, 0L, 0L)));

        UsageAdjustmentView view = service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, -500L, null, null, "全额冲正", null, null, null), audit());

        assertThat(view.outputTokensDelta()).isEqualTo(-500L);
    }

    @Test
    @DisplayName("a cancellation against a row the gateway recorded nothing for is allowed")
    void correctingAUsageMissingRowIsAllowed() {
        // usage_missing rows exist precisely so a provider-billed call the gateway
        // failed to meter can still be corrected.
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional
                .of(new AdjustmentTarget(EVENT, TENANT, CacheLevel.UPSTREAM, null, null, null, null, 0L, 0L, 0L, 0L)));

        UsageAdjustmentView view = service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, 700L, 300L, null, null, "补录漏记用量", null, null, null), audit());

        assertThat(view.inputTokensDelta()).isEqualTo(700L);
    }

    @Test
    @DisplayName("a cache-hit row carries no usage and cannot be adjusted")
    void cacheHitTargetIsRejected() {
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional
                .of(new AdjustmentTarget(EVENT, TENANT, CacheLevel.L1_HIT, null, null, null, null, 0L, 0L, 0L, 0L)));

        assertThatThrownBy(() -> service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, 10L, null, null, null, "调整缓存行", null, null, null), audit()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ADJUSTMENT_TARGET_HAS_NO_USAGE"));
    }

    @Test
    @DisplayName("a reversal negates the row it undoes, ignoring any deltas in the request")
    void reversalNegatesTheOriginal() {
        UUID originalId = UUID.randomUUID();
        UsageAdjustment original = new UsageAdjustment(originalId, TENANT, EVENT, AdjustmentType.USAGE, null, -200L,
                null, null, null, null, "上游账单修正", null, null, null, null, NOW, null);
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, -100L, 0L)));
        when(repository.findById(TENANT, originalId)).thenReturn(Optional.of(original));

        // The request carries a nonsensical +999; it must be ignored.
        UsageAdjustmentView view = service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, 999L, null, null, "撤销前次调整", null, originalId, null),
                audit());

        assertThat(view.reversalOfId()).isEqualTo(originalId);
        assertThat(view.outputTokensDelta()).isEqualTo(200L);
        verify(auditService).record(eq(TENANT), any(), eq("USAGE_ADJUSTMENT_REVERSED"), eq("USAGE_ADJUSTMENT"), any(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("reversing a reversal is refused so the ledger stays a flat list, not a chain")
    void reversalOfReversalIsRefused() {
        UUID reversalId = UUID.randomUUID();
        UsageAdjustment reversal = new UsageAdjustment(reversalId, TENANT, EVENT, AdjustmentType.USAGE, null, 200L,
                null, null, null, null, "撤销前次调整", null, null, UUID.randomUUID(), null, NOW, null);
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, -100L, 0L)));
        when(repository.findById(TENANT, reversalId)).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, -200L, null, null, "再撤销", null, reversalId, null),
                audit())).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("REVERSAL_OF_REVERSAL"));
    }

    @Test
    @DisplayName("a reversal must target an adjustment of the same usage event")
    void reversalAcrossEventsIsRefused() {
        UUID otherId = UUID.randomUUID();
        UsageAdjustment other = new UsageAdjustment(otherId, TENANT, UUID.randomUUID(), AdjustmentType.USAGE, null,
                -200L, null, null, null, null, "别处的调整", null, null, null, null, NOW, null);
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, 0L, 0L)));
        when(repository.findById(TENANT, otherId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, 200L, null, null, "跨行撤销", null, otherId, null), audit()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("REVERSAL_TARGET_MISMATCH"));
    }

    @Test
    @DisplayName("a retried submission returns the recorded row instead of booking it twice")
    void idempotentResubmissionDoesNotDoubleBook() {
        UsageAdjustment existing = new UsageAdjustment(UUID.randomUUID(), TENANT, EVENT, AdjustmentType.USAGE, null,
                -200L, null, null, null, null, "上游账单修正", null, null, null, null, NOW, "key-1");
        when(repository.findByIdempotencyKey(TENANT, "key-1")).thenReturn(Optional.of(existing));

        UsageAdjustmentView view = service.append(user(TENANT),
                new UsageAdjustmentRequest(REQUEST_ID, null, -200L, null, null, "上游账单修正", null, null, "key-1"),
                audit());

        assertThat(view.id()).isEqualTo(existing.id());
        verify(repository, never()).append(any());
    }

    @Test
    @DisplayName("an unknown request id is a tenant-scoped 404, never an existence oracle")
    void unknownRequestIdIsNotFound() {
        when(repository.findUsageEventIdByGatewayRequestId(TENANT, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.append(user(TENANT),
                new UsageAdjustmentRequest("nope", 1L, null, null, null, "原因", null, null, null), audit()))
                .isInstanceOf(ApiException.class).satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((ApiException) e).getCode()).isEqualTo("USAGE_EVENT_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("listing by request id returns the ledger for the resolved event")
    void listResolvesTheEvent() {
        UsageAdjustment row = new UsageAdjustment(UUID.randomUUID(), TENANT, EVENT, AdjustmentType.USAGE, 10L, null,
                null, null, null, null, "补录", null, null, null, null, NOW, null);
        when(repository.findByUsageEventId(TENANT, EVENT)).thenReturn(List.of(row));

        assertThat(service.listForRequest(user(TENANT), REQUEST_ID)).hasSize(1);
    }

    @Test
    @DisplayName("the audit summary escapes operator-supplied reason text")
    void auditSummaryEscapesReason() {
        when(repository.findAdjustmentTarget(TENANT, EVENT)).thenReturn(Optional.of(target(1_000L, 500L, 0L, 0L)));

        service.append(user(TENANT), new UsageAdjustmentRequest(REQUEST_ID, null, -1L, null, null,
                "quote \" and \\ backslash", null, null, null), audit());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(TENANT), any(), anyString(), anyString(), any(), summary.capture(), anyString());
        assertThat(summary.getValue()).contains("quote \\\" and \\\\ backslash");
    }

    private static AdjustmentTarget target(Long input, Long output, Long inputDelta, Long outputDelta) {
        return new AdjustmentTarget(EVENT, TENANT, CacheLevel.UPSTREAM, input, output, null, null, inputDelta,
                outputDelta, 0L, 0L);
    }

    private static AuditContext audit() {
        return AuditContext.human(UUID.randomUUID(), "req-1");
    }

    private static User user(UUID tenantId) {
        return new User(UUID.randomUUID(), tenantId, "admin", "Admin", new byte[]{1}, UserRole.SYSTEM_ADMIN,
                UserStatus.ACTIVE, false, 0, null, null, 0L, NOW, NOW);
    }
}
