package com.miqroera.miqrokey.controlplane.service;

import java.util.UUID;

/**
 * Audit attribution for admin mutations (issue #324): human sessions carry the
 * acting user; open-admin-API machine calls carry the issuing admin as actor
 * plus the machine key name as a {@code via} marker, so the trail stays
 * bi-attributable (machine key + responsible admin).
 */
public record AuditContext(UUID actorId, String requestId, String via) {

    public static AuditContext human(UUID actorId, String requestId) {
        return new AuditContext(actorId, requestId, null);
    }

    public static AuditContext machine(UUID issuerId, String keyName, String requestId) {
        return new AuditContext(issuerId, requestId,
                "admin-api:" + (keyName == null || keyName.isBlank() ? "?" : keyName));
    }
}
