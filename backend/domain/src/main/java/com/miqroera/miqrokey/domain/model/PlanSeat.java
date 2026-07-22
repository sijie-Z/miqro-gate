package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A seat within a team/enterprise subscription plan.
 */
public record PlanSeat(UUID id, UUID upstreamSubscriptionId, String externalSeatRef, UUID assignedUserId,
        String displayName, String seatStatus, Long quotaTotal, Long quotaUsed, Instant periodStart, Instant periodEnd,
        long version, Instant createdAt, Instant updatedAt) {
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_ASSIGNED = "ASSIGNED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_RELEASED = "RELEASED";
}
