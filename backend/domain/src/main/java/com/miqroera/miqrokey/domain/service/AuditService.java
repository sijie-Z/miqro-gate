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
