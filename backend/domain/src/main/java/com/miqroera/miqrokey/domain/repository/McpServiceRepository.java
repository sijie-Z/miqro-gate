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
     * Narrow status switch (#475): writes only the status column, never the health
     * telemetry — a stale full-row write could otherwise roll back the latest probe
     * result (health writes do not bump the version, so the optimistic predicate
     * cannot catch it).
     */
    McpService updateStatus(UUID tenantId, UUID serviceId, String status);

    /**
     * Replaces the whole row (status switch and admin edits with optimistic lock;
     * health telemetry goes through {@link #updateHealth}).
     *
     * <p>
     * Named {@code replace} on purpose (#1152): the {@code SET} clause must write
     * <em>all mutable columns owned by this method</em>. A deliberately narrow
     * write gets a purpose-named method ({@code updateStatus},
     * {@code updateHealth}, {@code updateBackendAuth}) instead — so "the name says
     * full replace but the SQL silently drops a column" stops being possible to
     * miss in review.
     *
     * <p>
     * "Owned by this method" is the operative phrase, not "every column in the
     * table": the backend-credential columns ({@code backend_auth_mode},
     * {@code backend_secret_*}) are <em>intentionally</em> owned by
     * {@link #updateBackendAuth} — credential ciphertext never enters this record.
     * Adding a new <em>ordinary</em> configuration column means adding it here.
     */
    McpService replace(McpService service, long expectedVersion);

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
