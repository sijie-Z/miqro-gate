package com.miqroera.miqrokey.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miqroera.miqrokey.controlplane.dto.ModelCallTimelineView;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.RequestUsageRecordRepository;
import com.miqroera.miqrokey.domain.usage.ModelCallRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Model-call timeline assembly (#705): phase derivation from the lifecycle
 * record, the partial-phase shapes (a call cancelled before upstream answered
 * has no later milestones — the absence is the diagnosis), and the
 * tenant-scoped not-found contract.
 */
@DisplayName("Model call timeline service")
class ModelCallTimelineServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String REQUEST_ID = "af453dd9-7267-42e3-ab5d-a105a12ce8d4";
    private static final Instant STARTED = Instant.parse("2026-09-17T02:00:00Z");

    private final RequestUsageRecordRepository repository = mock(RequestUsageRecordRepository.class);
    private final ModelCallTimelineService service = new ModelCallTimelineService(repository);

    @Test
    @DisplayName("forwarded call yields the three observed phases with measured elapsed times")
    void forwardedCallHasThreePhases() {
        Instant firstByte = STARTED.plus(120, ChronoUnit.MILLIS);
        Instant completed = STARTED.plus(1710, ChronoUnit.MILLIS);
        when(repository.findByGatewayRequestId(TENANT, REQUEST_ID))
                .thenReturn(Optional.of(record("SUCCEEDED", STARTED, firstByte, completed, 1710L, 120L)));

        ModelCallTimelineView view = service.timeline(user(TENANT), REQUEST_ID);

        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.phases()).extracting(ModelCallTimelineView.Phase::key).containsExactly("ACCEPTED", "FIRST_BYTE",
                "COMPLETED");
        assertThat(view.phases()).extracting(ModelCallTimelineView.Phase::elapsedMs).containsExactly(0L, 120L, 1710L);
        assertThat(view.timeToFirstByteMs()).isEqualTo(120L);
        assertThat(view.durationMs()).isEqualTo(1710L);
    }

    @Test
    @DisplayName("a call cancelled before upstream answered has only the ACCEPTED phase")
    void cancelledCallHasSinglePhase() {
        when(repository.findByGatewayRequestId(TENANT, REQUEST_ID))
                .thenReturn(Optional.of(record("CLIENT_CANCELLED", STARTED, null, null, null, null)));

        ModelCallTimelineView view = service.timeline(user(TENANT), REQUEST_ID);

        assertThat(view.status()).isEqualTo("CLIENT_CANCELLED");
        assertThat(view.phases()).extracting(ModelCallTimelineView.Phase::key).containsExactly("ACCEPTED");
        assertThat(view.firstByteAt()).isNull();
        assertThat(view.completedAt()).isNull();
    }

    @Test
    @DisplayName("an unfinalized record (IN_FLIGHT) is surfaced as-is rather than hidden")
    void unfinalizedRecordIsSurfaced() {
        when(repository.findByGatewayRequestId(TENANT, REQUEST_ID))
                .thenReturn(Optional.of(record("IN_FLIGHT", STARTED, null, null, null, null)));

        ModelCallTimelineView view = service.timeline(user(TENANT), REQUEST_ID);

        assertThat(view.status()).isEqualTo("IN_FLIGHT");
        assertThat(view.phases()).extracting(ModelCallTimelineView.Phase::key).containsExactly("ACCEPTED");
    }

    @Test
    @DisplayName("elapsed falls back to the timestamp delta when the measured value is absent")
    void elapsedFallsBackToTimestampDelta() {
        Instant firstByte = STARTED.plus(250, ChronoUnit.MILLIS);
        when(repository.findByGatewayRequestId(TENANT, REQUEST_ID))
                .thenReturn(Optional.of(record("SUCCEEDED", STARTED, firstByte, null, null, null)));

        ModelCallTimelineView view = service.timeline(user(TENANT), REQUEST_ID);

        assertThat(view.phases()).extracting(ModelCallTimelineView.Phase::elapsedMs).containsExactly(0L, 250L);
    }

    @Test
    @DisplayName("unknown request id is a tenant-scoped 404, never an existence oracle")
    void unknownRequestIsNotFound() {
        when(repository.findByGatewayRequestId(eq(TENANT), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.timeline(user(TENANT), REQUEST_ID)).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((ApiException) e).getCode()).isEqualTo("REQUEST_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("a blank request id is rejected before hitting the repository")
    void blankRequestIdIsRejected() {
        assertThatThrownBy(() -> service.timeline(user(TENANT), "  ")).isInstanceOf(ApiException.class).satisfies(e -> {
            assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((ApiException) e).getCode()).isEqualTo("REQUEST_ID_REQUIRED");
        });
        verify(repository, org.mockito.Mockito.never()).findByGatewayRequestId(any(), anyString());
    }

    @Test
    @DisplayName("the lookup is scoped to the caller's tenant")
    void lookupIsTenantScoped() {
        when(repository.findByGatewayRequestId(TENANT, REQUEST_ID))
                .thenReturn(Optional.of(record("SUCCEEDED", STARTED, null, null, null, null)));

        service.timeline(user(TENANT), REQUEST_ID);

        verify(repository).findByGatewayRequestId(TENANT, REQUEST_ID);
    }

    private static User user(UUID tenantId) {
        return new User(UUID.randomUUID(), tenantId, "admin", "Admin", new byte[]{1}, UserRole.SYSTEM_ADMIN,
                UserStatus.ACTIVE, false, 0, null, null, 0L, STARTED, STARTED);
    }

    private static ModelCallRecord record(String status, Instant startedAt, Instant firstByteAt, Instant completedAt,
            Long durationMs, Long ttfbMs) {
        return new ModelCallRecord(UUID.randomUUID(), REQUEST_ID, "upstream-req-1", TENANT, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "deepseek-flash", "OPENAI_CHAT_COMPLETIONS", true, status, startedAt, firstByteAt, completedAt,
                durationMs, ttfbMs, 200, false, false, 0, 39L, 70L, null, null);
    }
}
