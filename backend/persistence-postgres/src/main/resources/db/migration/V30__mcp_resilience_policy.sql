-- ============================================================================
-- V30: mcp_resilience_policy — F12/F13 per-MCP-service resilience config.
--
-- Retry gate (F12, doc 134831) and circuit breaker (F13, doc 134859),
-- adapted from the Tencent HTTP-to-MCP tool granularity to the whole MCP
-- service egress (the gateway talks one MCP endpoint per service; per-tool
-- isolation is expressed at runtime through the breaker bucket = tool name).
--
-- Both features are DEFAULT OFF: a missing row (or all-false flags) means the
-- data plane behaves exactly as before. Retry conditions CSV ∈
-- SERVER_5XX|CONNECTION_FAILURE|TIMEOUT (at least one when enabled; retries
-- only before the first upstream response byte). Non-idempotent tool calls
-- (POST/PUT/PATCH tool rows) additionally need retry_idempotency_confirmed.
--
-- Breaker: sliding window stats (window_seconds), min-request guard,
-- error-ratio trigger (error_status_codes CSV 400..599, ≤32) and optional
-- slow-call trigger; slow_call_ms must stay below the service's own
-- check_timeout_seconds (validated by the admin API, documented in
-- api-contract §5.25). open_seconds → half-open probes (probe_count /
-- probe_success) → close. breaker_skip_retry: while open, retries are
-- skipped (doc recommendation stays ON).
-- ============================================================================

CREATE TABLE mcp_resilience_policy (
    mcp_service_id              uuid          NOT NULL PRIMARY KEY REFERENCES mcp_services (id) ON DELETE CASCADE,
    tenant_id                   uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    -- F12 retry gate
    retry_enabled               boolean       NOT NULL DEFAULT FALSE,
    retry_max                   integer       NOT NULL DEFAULT 1
                                CHECK (retry_max BETWEEN 1 AND 5),
    retry_conditions            varchar(128)  NOT NULL DEFAULT '',
    retry_idempotency_confirmed boolean       NOT NULL DEFAULT FALSE,
    -- F13 circuit breaker
    breaker_enabled             boolean       NOT NULL DEFAULT FALSE,
    breaker_window_seconds      integer       NOT NULL DEFAULT 10
                                CHECK (breaker_window_seconds BETWEEN 1 AND 60),
    breaker_min_requests        integer       NOT NULL DEFAULT 10
                                CHECK (breaker_min_requests BETWEEN 1 AND 100),
    breaker_error_enabled       boolean       NOT NULL DEFAULT TRUE,
    breaker_error_ratio         integer       NOT NULL DEFAULT 50
                                CHECK (breaker_error_ratio BETWEEN 1 AND 100),
    breaker_error_status_codes  varchar(256)  NOT NULL DEFAULT '500,502,503,504',
    breaker_slow_enabled        boolean       NOT NULL DEFAULT FALSE,
    breaker_slow_call_ms        integer       NOT NULL DEFAULT 3000
                                CHECK (breaker_slow_call_ms BETWEEN 100 AND 60000),
    breaker_slow_ratio          integer       NOT NULL DEFAULT 80
                                CHECK (breaker_slow_ratio BETWEEN 1 AND 100),
    breaker_open_seconds        integer       NOT NULL DEFAULT 30
                                CHECK (breaker_open_seconds BETWEEN 5 AND 600),
    breaker_probe_count         integer       NOT NULL DEFAULT 3
                                CHECK (breaker_probe_count BETWEEN 1 AND 10),
    breaker_probe_success       integer       NOT NULL DEFAULT 2
                                CHECK (breaker_probe_success BETWEEN 1 AND 10
                                    AND breaker_probe_success <= breaker_probe_count),
    breaker_skip_retry          boolean       NOT NULL DEFAULT TRUE,
    version                     integer       NOT NULL DEFAULT 0,
    created_by                  uuid,
    updated_by                  uuid,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now()
);
