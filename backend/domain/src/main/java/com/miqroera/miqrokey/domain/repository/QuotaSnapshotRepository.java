package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.usage.QuotaSnapshot;

import java.util.List;
import java.util.UUID;

/**
 * Read/write access to {@code quota_snapshots} (V9, G4.2). Snapshots are
 * append-only history; readers take the latest row per scope.
 */
public interface QuotaSnapshotRepository {

    QuotaSnapshot insert(QuotaSnapshot snapshot);

    /**
     * Latest row per scope (subscription / seat / credential) for one subscription
     * — the "current status" view for the admin UI.
     */
    List<QuotaSnapshot> findLatestPerScope(UUID tenantId, UUID subscriptionId);

    /**
     * Latest row per scope across every subscription of the tenant — the
     * tenant-wide "current quota status" view for the external billing API.
     */
    List<QuotaSnapshot> findLatestForTenant(UUID tenantId);

    /** Newest {@code limit} rows for one subscription (history view). */
    List<QuotaSnapshot> findLatestBySubscription(UUID tenantId, UUID subscriptionId, int limit);
}
