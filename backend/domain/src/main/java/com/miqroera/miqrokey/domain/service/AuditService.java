package com.miqroera.miqrokey.domain.service;

import java.util.UUID;

/**
 * Writes security audit events without persisting passwords, session tokens,
 * CSRF secrets, or sensitive request bodies.
 *
 * <p>
 * Implementations must ensure the caller cannot accidentally log secret
 * material through the free-form {@code changeSummary} parameter. Callers are
 * responsible for constructing summary strings that exclude secrets.
 * </p>
 */
public interface AuditService {

    /**
     * Acquires the audit chain's transaction-scoped lock now, rather than leaving
     * it to the first {@link #record} call.
     *
     * <p>
     * The chain lock is global — every audit write in the cluster serialises on it
     * — and the audit insert takes a foreign-key {@code KEY SHARE} on the tenant
     * row. A transaction that will take a row lock the audit path also needs (the
     * tenant row is the common one) must therefore settle its order first: <b>chain
     * lock before any row lock</b>. Two transactions taking those two locks in
     * opposite orders deadlock — PostgreSQL reports exactly that cycle (#995).
     * </p>
     *
     * <p>
     * Must be called inside a transaction: the lock is transaction-scoped, so
     * calling it outside one would take and immediately release it, silently
     * removing the serialisation it appears to provide.
     * </p>
     */
    void acquireChainLock();

    /**
     * Record an audit event.
     *
     * @param tenantId
     *            the tenant
     * @param actorId
     *            the user performing the action (may be null for system-initiated)
     * @param action
     *            stable action identifier (e.g., "LOGIN", "PASSWORD_CHANGE")
     * @param targetType
     *            type of the target resource (e.g., "USER", "SESSION")
     * @param targetId
     *            ID of the target resource
     * @param changeSummary
     *            JSON-safe summary (must not contain passwords, tokens, CSRF
     *            secrets, or full request bodies)
     * @param requestId
     *            correlation ID from the current request
     */
    void record(UUID tenantId, UUID actorId, String action, String targetType, UUID targetId, String changeSummary,
            String requestId);
}
