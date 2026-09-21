package com.miqroera.miqrokey.domain.model;

/**
 * Terminal outcome of one MCP gateway request as recorded in
 * {@code mcp_access_log} (F15): the gateway-level decision / upstream result of
 * the JSON-RPC envelope exchange. {@code FORWARDED} rows carry the upstream
 * HTTP status in {@code http_status}; the access-control rows are recorded with
 * the client-facing 4xx status. Pre-resolution failures (401 unknown consumer
 * key, 404 unknown service) are not logged — no trustworthy identity exists at
 * that point.
 */
public enum McpAccessStatus {
    FORWARDED, SERVICE_DENIED, TOOL_DENIED, TOOL_UNAVAILABLE, INVALID_ENVELOPE, UPSTREAM_FAILURE, CIRCUIT_OPEN
}
