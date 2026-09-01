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

    List<Agent> findAllByTenantId(UUID tenantId);

    /** Status update with optimistic version bump; returns the stored row. */
    Agent updateStatus(UUID tenantId, UUID agentId, String status, long expectedVersion);
}
