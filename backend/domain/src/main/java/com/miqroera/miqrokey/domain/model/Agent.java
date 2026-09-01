package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A managed smart-agent resource (P3.1, {@code agents} V17) modeled after the
 * Alibaba AI Gateway agent topology: the agent's egress is bound to one
 * upstream credential (the provider product follows from the credential), and
 * usage observability aggregates by that credential for the per-agent view.
 */
public record Agent(UUID id, UUID tenantId, String name, String description, UUID upstreamCredentialId, String status,
        long version, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public Agent {
        if (id == null || tenantId == null || name == null || name.isBlank() || upstreamCredentialId == null) {
            throw new IllegalArgumentException("id/tenantId/name/upstreamCredentialId are required");
        }
        if (status == null || !(status.equals("ACTIVE") || status.equals("DISABLED"))) {
            throw new IllegalArgumentException("status must be ACTIVE or DISABLED");
        }
    }
}
