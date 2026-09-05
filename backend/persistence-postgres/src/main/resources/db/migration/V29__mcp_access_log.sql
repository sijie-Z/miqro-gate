-- ============================================================================
-- V29: mcp_access_log — F15 pure-metadata access log of the MCP invocation
-- proxy (Tencent AI gateway doc raw 16: aigw.mcp.* family).
--
-- One row per MCP gateway request whose caller identity and target service
-- were resolvable (i.e. requests that passed consumer authentication and
-- service-name resolution in McpProxyController). Pre-resolution failures
-- (401 unknown key, 404 unknown service) have no trustworthy identity and
-- stay in the request logs only — documented in api-contract §5.23.
--
-- Recorded outcomes: FORWARDED (upstream answered; http_status = upstream
-- HTTP status), SERVICE_DENIED / TOOL_DENIED / TOOL_UNAVAILABLE (access
-- control, Tencent doc 134890), INVALID_ENVELOPE (non-JSON-RPC body) and
-- UPSTREAM_FAILURE (no upstream response within the per-envelope budget).
--
-- Metadata only: tool name comes from the JSON-RPC envelope method/name, and
-- neither tool arguments nor response bodies are ever read or stored.
-- ============================================================================

CREATE TABLE mcp_access_log (
    id                  uuid          NOT NULL,
    tenant_id           uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    service_id          uuid          NOT NULL,
    service_name        varchar(128)  NOT NULL,
    consumer_id         uuid          NOT NULL,
    consumer_name       varchar(128)  NOT NULL,
    rpc_method          varchar(64),
    tool_name           varchar(128),
    status              varchar(32)   NOT NULL
                        CHECK (status IN ('FORWARDED', 'SERVICE_DENIED', 'TOOL_DENIED', 'TOOL_UNAVAILABLE',
                            'INVALID_ENVELOPE', 'UPSTREAM_FAILURE')),
    http_status         integer,
    gateway_request_id  varchar(64)   NOT NULL,
    occurred_at         timestamptz   NOT NULL
);

-- The gateway flushes asynchronously and retries failed batches; a retried
-- flush must never double-write one request (same idempotency convention as
-- the usage writer).
CREATE UNIQUE INDEX uq_mcp_access_log_request
    ON mcp_access_log (tenant_id, gateway_request_id);

-- Admin query shapes: newest-first listing per tenant, optional service /
-- consumer / time-window filters.
CREATE INDEX ix_mcp_access_log_tenant_occurred
    ON mcp_access_log (tenant_id, occurred_at DESC);
CREATE INDEX ix_mcp_access_log_service
    ON mcp_access_log (tenant_id, service_name, occurred_at DESC);
CREATE INDEX ix_mcp_access_log_consumer
    ON mcp_access_log (tenant_id, consumer_name, occurred_at DESC);
