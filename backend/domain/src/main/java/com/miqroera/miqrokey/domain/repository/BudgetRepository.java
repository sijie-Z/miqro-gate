package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Budget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code budget} (V7, G8.2): monthly per-project budgets. Upsert is
 * keyed on (tenant, project, month) — the admin UI edits the current month's
 * plan in place.
 */
public interface BudgetRepository {

    /** Insert or update the (project, month) row; returns the stored row. */
    Budget upsert(Budget budget);

    Optional<Budget> findByProjectAndMonth(UUID tenantId, UUID projectId, String month);

    List<Budget> findAllByTenantAndMonth(UUID tenantId, String month);

    /** True when a row was removed. */
    boolean delete(UUID tenantId, UUID projectId, String month);
}
