-- V46: mcp_tool_retry_policy -- F12 retry override at TOOL granularity
-- (issue #360, I13, raw doc 12). One row per tool overrides the retry fields
-- of the service-level policy for that tool's tools/call traffic (the breaker
-- stays service-level). Missing row = no override. Mirrors the retry subset
-- of mcp_resilience_policy (V30): retry conditions CSV is a non-empty subset
-- of SERVER_5XX|CONNECTION_FAILURE|TIMEOUT when enabled, and non-idempotent
-- tool calls (POST/PUT/PATCH tool rows) need retry_idempotency_confirmed.
-- ============================================================================
CREATE TABLE mcp_tool_retry_policy (
    mcp_tool_id                 uuid          NOT NULL PRIMARY KEY REFERENCES mcp_tools (id) ON DELETE CASCADE,
    tenant_id                   uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    retry_enabled               boolean       NOT NULL DEFAULT FALSE,
    retry_max                   integer       NOT NULL DEFAULT 1
                                CHECK (retry_max BETWEEN 1 AND 5),
    retry_conditions            varchar(128)  NOT NULL DEFAULT '',
    retry_idempotency_confirmed boolean       NOT NULL DEFAULT FALSE,
    version                     bigint        NOT NULL DEFAULT 0,
    updated_by                  uuid          REFERENCES users (id) ON DELETE SET NULL,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now()
);
