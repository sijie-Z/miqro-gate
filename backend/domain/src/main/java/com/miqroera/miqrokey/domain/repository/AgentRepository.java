package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.Agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code agents} (V17, P3.1 managed smart agents).
 */
public interface AgentRepository {

    Agent insert(Agent agent);

    Optional<Agent> findByIdAndTenantId(UUID id, UUID tenantId);

    /** True when another agent already binds the credential (1:1 rule). */
    boolean existsByCredentialId(UUID tenantId, UUID credentialId);

    /**
     * The ACTIVE agent binding the credential, if any (#714). A disabled agent
     * keeps its historical binding for usage attribution but no longer pins the
     * credential, so disabling the agent is the unlink path.
     */
    Optional<Agent> findActiveByCredentialId(UUID tenantId, UUID credentialId);

    List<Agent> findAllByTenantId(UUID tenantId);

    /** Status update with optimistic version bump; returns the stored row. */
    Agent updateStatus(UUID tenantId, UUID agentId, String status, long expectedVersion);
}
