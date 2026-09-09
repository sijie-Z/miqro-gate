package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Masked API-consumer view (ADR-0010/0011): name, display-only key prefix and
 * status. The API key plaintext is returned only once at creation; the JWT
 * verification key is never returned — only its fingerprint and set time.
 * {@code capabilities} is the issue #316 channel scope: null = full access.
 */
public record ApiConsumerView(UUID id, String name, String keyPrefix, String status, String jwtKeyFingerprint,
        Instant jwtKeySetAt, Instant createdAt, List<String> capabilities) {
}
