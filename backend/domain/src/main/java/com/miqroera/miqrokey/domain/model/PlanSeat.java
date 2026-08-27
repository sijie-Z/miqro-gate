package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A seat within a team/enterprise subscription plan.
 */
public record PlanSeat(UUID id, UUID tenantId, UUID upstreamSubscriptionId, String externalSeatRef, UUID assignedUserId,
        String displayName, SeatStatus seatStatus, Long quotaTotal, Long quotaUsed, Instant periodStart,
        Instant periodEnd, long version, Instant createdAt, Instant updatedAt) {
}
