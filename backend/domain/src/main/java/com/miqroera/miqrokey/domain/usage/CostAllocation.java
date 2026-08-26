package com.miqroera.miqrokey.domain.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One per-period cost allocation row (G4.3, {@code cost_allocations} V10): the
 * fixed-cost share and metered usage cost attributed to a project (or user) for
 * a subscription period, plus the token weight used by the allocation algorithm
 * and the algorithm version (so re-allocation is idempotent and versioned
 * instead of overwriting history).
 */
public record CostAllocation(UUID id, UUID tenantId, UUID subscriptionId, Instant periodStart, Instant periodEnd,
        CostAllocationTargetType targetType, UUID targetId, BigDecimal fixedCost, BigDecimal usageCost,
        long weightTokens, BigDecimal allocatedAmount, String currency, String algorithmVersion, Instant generatedAt,
        Instant createdAt) {

    public CostAllocation {
        if (id == null || tenantId == null || subscriptionId == null || periodStart == null || periodEnd == null
                || targetType == null || targetId == null || currency == null || algorithmVersion == null
                || generatedAt == null || createdAt == null) {
            throw new IllegalArgumentException("required fields must not be null");
        }
    }
}
