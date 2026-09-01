package com.miqroera.miqrokey.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Monthly budget per project (V7, {@code budget} table, G8.2). Alerting-only —
 * a budget never blocks traffic, matching the product decision "no budget
 * blocking". The spend watermark is computed at read time from usage cost
 * allocation (per-project {@code projectAllocated}), so the table only stores
 * the plan: amount, currency, alert threshold percentage and status.
 */
public record Budget(UUID id, UUID tenantId, UUID projectId, String periodMonth, BigDecimal amount, String currency,
        BigDecimal alertThresholdPct, String status, long version, Instant createdAt, Instant updatedAt) {

    public Budget {
        if (id == null || tenantId == null || projectId == null) {
            throw new IllegalArgumentException("id/tenantId/projectId are required");
        }
        if (periodMonth == null || !periodMonth.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("periodMonth must be YYYY-MM");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (alertThresholdPct == null || alertThresholdPct.signum() < 0
                || alertThresholdPct.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("alertThresholdPct must be within 0..100");
        }
        if (status == null || !(status.equals("ACTIVE") || status.equals("PAUSED"))) {
            throw new IllegalArgumentException("status must be ACTIVE or PAUSED");
        }
    }
}
