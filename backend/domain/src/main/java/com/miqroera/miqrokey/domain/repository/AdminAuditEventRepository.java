package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.AdminAuditEvent;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link AdminAuditEvent} append-only entities.
 */
public interface AdminAuditEventRepository {

    AdminAuditEvent insert(AdminAuditEvent event);

    /**
     * Returns the most recent audit event (by {@code chain_position DESC}) for
     * chain-link construction. May return null if the event table is empty.
     *
     * <p>
     * {@code chain_position} is a database-monotonic identity/sequence assigned at
     * INSERT time — it reflects the true causal commit order under concurrent
     * advisory-lock writers. JVM clock and random UUID are deliberately not used
     * for head selection because they can be causally out of order (pre-lock
     * timestamps may disagree with commit order).
     * </p>
     *
     * <p>
     * Callers must hold {@link #acquireChainLock()} inside the same transaction
     * before calling this method to guarantee a valid predecessor for the hash
     * chain under concurrent writers.
     * </p>
     */
    AdminAuditEvent findMostRecent();

    /**
     * Acquires a PostgreSQL transaction-scoped advisory lock for serializing audit
     * chain writes across concurrent transactions and JVM instances. The lock is
     * released automatically when the current transaction commits or rolls back.
     *
     * <p>
     * A fixed global key is acceptable because the audit chain is currently global
     * (not per-tenant). Using {@code pg_advisory_xact_lock} is correct even when
     * the table is empty — unlike {@code SELECT ... FOR UPDATE}, which cannot lock
     * a non-existent row.
     * </p>
     */
    void acquireChainLock();

    /**
     * @deprecated Use {@link #acquireChainLock()} + {@link #findMostRecent()}
     *             inside the same transaction instead. FOR UPDATE on the most
     *             recent row is insufficient when the table is empty.
     */
    @Deprecated
    AdminAuditEvent findMostRecentForUpdate();

    List<AdminAuditEvent> findByTargetTypeAndTargetId(String targetType, UUID targetId);

    List<AdminAuditEvent> findByActorId(UUID actorId, int limit);
}
