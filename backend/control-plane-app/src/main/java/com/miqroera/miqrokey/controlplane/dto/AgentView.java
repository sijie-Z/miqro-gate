package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Managed-agent view (P3.1): egress credential with the derived provider
 * product (credential → subscription → product). {@code version} is the
 * optimistic-lock token the edit form must echo back on PATCH (#824).
 */
public record AgentView(UUID id, String name, String description, UUID credentialId, String credentialName,
        UUID providerProductId, String providerProductName, String status, long version, Instant createdAt) {
}
