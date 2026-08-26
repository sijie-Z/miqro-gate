package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.usage.CostAllocation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read/write access to {@code cost_allocations} (V10, G4.3). Rows are
 * idempotent per (subscription, period, target, algorithm version) — an upsert
 * re-runs the same allocation without duplicating history.
 */
public interface CostAllocationRepository {

    /** Inserts or replaces the row for its unique key (same version). */
    CostAllocation upsert(CostAllocation allocation);

    /** All rows for one subscription period window, newest generated first. */
    List<CostAllocation> findByPeriod(UUID tenantId, UUID subscriptionId, Instant periodStart, Instant periodEnd);
}
