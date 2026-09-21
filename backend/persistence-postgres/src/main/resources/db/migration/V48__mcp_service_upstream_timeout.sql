-- V48: mcp_services.upstream_timeout_ms (#I20, Tencent raw doc 135906).
-- The MCP data plane's per-attempt upstream budget was hardcoded at 60s; doc
-- 135906 exposes it as a per-service backend-config field ("超时时间",
-- default 60000 ms). The route snapshot carries it to the gateway hot path.
-- The circuit breaker's slow-call threshold is validated against THIS value
-- (doc 134859 baseline = the backend request timeout), rebased from the
-- health-probe check_timeout_seconds.
ALTER TABLE mcp_services
    ADD COLUMN upstream_timeout_ms integer NOT NULL DEFAULT 60000
        CHECK (upstream_timeout_ms BETWEEN 1000 AND 600000);
