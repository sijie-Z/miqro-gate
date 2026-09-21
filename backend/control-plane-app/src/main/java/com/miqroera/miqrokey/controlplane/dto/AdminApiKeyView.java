package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.AdminApiKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Open admin API key view (ADR-0015) — digest never leaves the store. */
public record AdminApiKeyView(UUID id, String name, String prefix, Instant createdAt, Instant expiresAt,
        Instant revokedAt, boolean active, List<String> capabilities) {

    public static AdminApiKeyView from(AdminApiKey key) {
        return new AdminApiKeyView(key.id(), key.name(), key.keyPrefix(), key.createdAt(), key.expiresAt(),
                key.revokedAt(), key.active(), key.capabilities());
    }
}
