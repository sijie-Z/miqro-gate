package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire shape of one forwarded access-log entry (I19): the OTel-style
 * {@code aigw.mcp.*} field set of Tencent raw 16, restricted to exactly the
 * metadata the entry carries — never tool arguments or response bodies.
 * Timestamps are ISO-8601 strings, so sinks need no date module to serialize.
 */
final class McpAccessLogJson {

    private McpAccessLogJson() {
    }

    static Map<String, Object> of(McpAccessLogEntry entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aigw.mcp.event_id", String.valueOf(entry.id()));
        out.put("aigw.mcp.tenant_id", String.valueOf(entry.tenantId()));
        out.put("aigw.mcp.service_id", String.valueOf(entry.serviceId()));
        out.put("aigw.mcp.service", entry.serviceName());
        out.put("aigw.mcp.consumer_id", String.valueOf(entry.consumerId()));
        out.put("aigw.mcp.consumer", entry.consumerName());
        out.put("aigw.mcp.rpc_method", entry.rpcMethod());
        out.put("aigw.mcp.tool", entry.toolName());
        out.put("aigw.mcp.outcome", entry.status() == null ? null : entry.status().name());
        out.put("aigw.mcp.http_status", entry.httpStatus());
        out.put("aigw.mcp.request_id", entry.gatewayRequestId());
        out.put("aigw.mcp.session_id", entry.sessionId());
        out.put("aigw.mcp.ttfb_ms", entry.ttfbMs());
        out.put("aigw.mcp.occurred_at", entry.occurredAt() == null ? null : entry.occurredAt().toString());
        return out;
    }
}
