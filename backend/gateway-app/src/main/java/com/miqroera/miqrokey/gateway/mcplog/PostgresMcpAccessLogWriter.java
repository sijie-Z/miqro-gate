package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link McpAccessLogWriter} over the gateway DataSource (same batching shape
 * as the usage writer): one idempotent multi-row insert per flush.
 */
public final class PostgresMcpAccessLogWriter implements McpAccessLogWriter {

    private static final Logger log = LoggerFactory.getLogger(PostgresMcpAccessLogWriter.class);

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresMcpAccessLogWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void writeBatch(List<McpAccessLogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> params = new ArrayList<>(entries.size());
        for (McpAccessLogEntry e : entries) {
            params.add(new MapSqlParameterSource().addValue("id", e.id()).addValue("tenantId", e.tenantId())
                    .addValue("serviceId", e.serviceId()).addValue("serviceName", e.serviceName())
                    .addValue("consumerId", e.consumerId()).addValue("consumerName", e.consumerName())
                    .addValue("rpcMethod", e.rpcMethod()).addValue("toolName", e.toolName())
                    .addValue("status", e.status().name()).addValue("httpStatus", e.httpStatus())
                    .addValue("gatewayRequestId", e.gatewayRequestId())
                    .addValue("occurredAt", Timestamp.from(e.occurredAt())));
        }
        jdbc.batchUpdate("""
                INSERT INTO mcp_access_log (id, tenant_id, service_id, service_name, consumer_id, consumer_name,
                    rpc_method, tool_name, status, http_status, gateway_request_id, occurred_at)
                VALUES (:id, :tenantId, :serviceId, :serviceName, :consumerId, :consumerName, :rpcMethod, :toolName,
                    :status, :httpStatus, :gatewayRequestId, :occurredAt)
                ON CONFLICT (tenant_id, gateway_request_id) DO NOTHING
                """, params.toArray(new MapSqlParameterSource[0]));
    }
}
