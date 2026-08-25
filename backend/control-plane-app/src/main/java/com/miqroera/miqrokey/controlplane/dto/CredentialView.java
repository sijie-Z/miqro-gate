package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Masked upstream-credential view (api-contract §5). Contains only metadata:
 * status, the active version, and a short hex fingerprint prefix. The plaintext
 * secret and the full fingerprint never leave the control plane.
 */
public record CredentialView(UUID id, String name, UUID subscriptionId, String status, UUID activeVersionId,
        String fingerprintPrefix, Instant lastValidatedAt, String lastValidationError, long version, Instant createdAt,
        Instant updatedAt) {
}
