package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code mcp_access_log} row (V29, F15): pure metadata of an MCP proxy call
 * — identity, envelope method, tool name and outcome. Tool arguments and
 * response bodies never appear anywhere in the record.
 */
public record McpAccessLogEntry(UUID id, UUID tenantId, UUID serviceId, String serviceName, UUID consumerId,
        String consumerName, String rpcMethod, String toolName, McpAccessStatus status, Integer httpStatus,
        String gatewayRequestId, Instant occurredAt) {
}
