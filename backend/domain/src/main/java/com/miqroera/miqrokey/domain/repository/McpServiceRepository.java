package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.model.McpService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_services} (V20, P3.4 MCP management).
 */
public interface McpServiceRepository {

    McpService insert(McpService service);

    Optional<McpService> findByIdAndTenantId(UUID id, UUID tenantId);

    List<McpService> findAllByTenantId(UUID tenantId);

    /** ONLINE services only — the health checker's probe list. */
    List<McpService> findAllOnlineByTenantId(UUID tenantId);

    /**
     * Health telemetry write (#415, mirrors {@code InternalServiceRepository}):
     * health columns only — no version check and no version bump — so the probe
     * cycle never races an admin edit that runs under optimistic locking.
     */
    McpService updateHealth(UUID tenantId, UUID serviceId, String healthStatus, Instant checkedAt,
            int consecutiveFailures, int consecutiveSuccesses);

    /**
     * Replaces the full row (status switch and admin edits with optimistic lock;
     * health telemetry goes through {@link #updateHealth}).
     */
    McpService update(McpService service, long expectedVersion);

    /**
     * Sets the upstream backend authentication (#320): {@code API_KEY} with an
     * encrypted secret triple, or {@code VISITOR} with {@code null} (clears the
     * ciphertext). Bumps the version; the plaintext never passes through here.
     */
    McpService updateBackendAuth(UUID id, UUID tenantId, String mode, EncryptedSecret encryptedSecret);

    /**
     * The encrypted upstream credential (#320) for server-side use only (tool sync;
     * the gateway reads it through its own snapshot); never serialized into admin
     * responses.
     */
    Optional<EncryptedSecret> findBackendSecret(UUID id, UUID tenantId);

    /** Sets the per-service data-plane upstream budget (I20, doc 135906). */
    McpService updateUpstreamTimeout(UUID id, UUID tenantId, int upstreamTimeoutMs);
}
