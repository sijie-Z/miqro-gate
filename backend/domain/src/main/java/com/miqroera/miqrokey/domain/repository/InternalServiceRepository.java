package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.InternalService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code services} (V18, P3.2 internal service registry).
 */
public interface InternalServiceRepository {

    InternalService insert(InternalService service);

    Optional<InternalService> findByIdAndTenantId(UUID id, UUID tenantId);

    List<InternalService> findAllByTenantId(UUID tenantId);

    /** Status update with optimistic version bump; returns the stored row. */
    /**
     * Compare-and-set status switch: succeeds only while the service is not already
     * in {@code status} (0 rows otherwise). Deliberately NOT version-guarded —
     * health telemetry updates must not make a status switch lose (#361); status
     * switches only conflict with other status switches.
     */
    InternalService updateStatus(UUID tenantId, UUID serviceId, String status);

    /**
     * Health telemetry write (#361): touches only the health columns and does NOT
     * bump {@code version}, so admin edits (version-guarded) never lose to a probe.
     */
    InternalService updateHealth(UUID tenantId, UUID serviceId, String healthStatus, Instant checkedAt,
            int consecutiveFailures, int consecutiveSuccesses);

    /** ACTIVE services only — the health checker's probe list (#326). */
    List<InternalService> findAllActiveByTenantId(UUID tenantId);

    /**
     * Replaces the whole row with optimistic lock (admin edits and status switch).
     *
     * <p>
     * Named {@code replace} on purpose (#1152): the {@code SET} clause must write
     * <em>all mutable columns owned by this method</em>. A deliberately narrow
     * write gets a purpose-named method ({@code updateStatus},
     * {@code updateHealth}) instead.
     *
     * <p>
     * "Owned by this method" is the operative phrase, not "every column in the
     * table": {@code created_by} / {@code created_at} are system fields written by
     * {@link #insert}, not here.
     */
    InternalService replace(InternalService service, long expectedVersion);
}
