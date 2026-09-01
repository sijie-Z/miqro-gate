package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Managed-agent view (P3.1): egress credential with the derived provider
 * product (credential → subscription → product).
 */
public record AgentView(UUID id, String name, String description, UUID credentialId, String credentialName,
        UUID providerProductId, String providerProductName, String status, Instant createdAt) {
}
