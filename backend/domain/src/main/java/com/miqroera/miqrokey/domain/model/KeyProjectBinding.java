package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The authorization authority for label routing: which project a virtual key
 * may address, and through which grant. The gateway routes a presented key's
 * label to the project only if an ACTIVE binding exists here — the label itself
 * is NOT trusted.
 *
 * <p>
 * ADR-0018 (single key, multiple projects): a key may hold one binding row per
 * project. Each row carries its own {@code grantId} — the credential, product
 * and granted-model scope used when the request resolves to this binding.
 * </p>
 */
public record KeyProjectBinding(UUID id, UUID tenantId, UUID virtualKeyId, UUID projectId, UUID grantId,
        KeyProjectBindingStatus status, long version, Instant createdAt, Instant updatedAt) {
}
