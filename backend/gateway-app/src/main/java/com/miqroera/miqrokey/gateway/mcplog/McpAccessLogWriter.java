package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;

import java.util.List;

/**
 * JDBC batch writer of {@code mcp_access_log} rows. Never runs on the Reactor
 * event loop — the queue flush task owns it. Writes are idempotent (partial
 * unique index on (tenant_id, gateway_request_id), {@code ON CONFLICT DO
 * NOTHING}), so a retried flush can never double-write one request.
 */
public interface McpAccessLogWriter {

    void writeBatch(List<McpAccessLogEntry> entries);
}
