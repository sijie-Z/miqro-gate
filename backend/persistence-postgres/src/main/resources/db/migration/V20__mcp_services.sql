-- V20: MCP services (P3.4, Tencent AI gateway MCP management)
-- MCP servers registered for agent tool access. Online/offline is a manual
-- switch (never auto-recovered, mirroring Tencent); health checking probes
-- the endpoint on a schedule and reports HEALTHY/UNHEALTHY state driven by
-- fail/recover thresholds.

CREATE TABLE mcp_services (
    id                    uuid          PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name                  varchar(200)  NOT NULL,
    description           varchar(2000),
    endpoint              varchar(2048) NOT NULL,   -- https base URL
    transport             varchar(32)   NOT NULL DEFAULT 'STREAMABLE_HTTP'
                          CHECK (transport IN ('STREAMABLE_HTTP', 'SSE')),
    status                varchar(16)   NOT NULL DEFAULT 'ONLINE'
                          CHECK (status IN ('ONLINE', 'OFFLINE')),
    health_status         varchar(16)   NOT NULL DEFAULT 'UNKNOWN'
                          CHECK (health_status IN ('UNKNOWN', 'HEALTHY', 'UNHEALTHY')),
    health_checked_at     timestamptz,
    consecutive_failures  integer       NOT NULL DEFAULT 0,
    consecutive_successes integer       NOT NULL DEFAULT 0,
    check_interval_seconds integer      NOT NULL DEFAULT 30,
    check_timeout_seconds integer       NOT NULL DEFAULT 5,
    fail_threshold        integer       NOT NULL DEFAULT 3,
    recover_threshold     integer       NOT NULL DEFAULT 1,
    check_path            varchar(512)  NOT NULL DEFAULT '/health',
    version               bigint        NOT NULL DEFAULT 0,
    created_by            uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_mcp_services_tenant_name ON mcp_services (tenant_id, name);
CREATE INDEX idx_mcp_services_health ON mcp_services (tenant_id, health_status);
