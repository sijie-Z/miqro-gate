package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.McpRouteRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code mcp_route_rule} (V28, F11 route rules).
 */
public interface McpRouteRuleRepository {

    McpRouteRule insert(McpRouteRule rule);

    Optional<McpRouteRule> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Rules of one service, highest priority first (deterministic by name). */
    List<McpRouteRule> findAllByService(UUID tenantId, UUID mcpServiceId);

    /** Full update of the editable fields with an optimistic version bump. */
    McpRouteRule update(McpRouteRule rule, long expectedVersion);

    /** Status update with optimistic version bump; returns the stored row. */
    McpRouteRule updateStatus(UUID tenantId, UUID ruleId, String status, long expectedVersion);

    void deleteById(UUID tenantId, UUID ruleId);
}
