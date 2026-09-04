-- ============================================================================
-- 28. MCP 路由规则 (F11, Tencent AI gateway doc 135482)
--     Rules decide which inbound requests may reach an MCP service; every rule
--     shares the same upstream (the service endpoint). Match conditions inside
--     a rule are AND-ed; REGEX modes use RE2 semantics (validated app-side).
--
--     The system-generated `default` rule (priority 0, no matchers, status
--     ENABLED) is the immutable catch-all that keeps the service reachable;
--     custom rules default to priority 1000 and may be edited/disabled/deleted.
--
--     created_by is nullable: default rows are created by the system, not by
--     an admin session (custom rows always carry the acting admin).
-- ============================================================================
CREATE TABLE mcp_route_rule (
    id                 uuid         PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    mcp_service_id     uuid         NOT NULL REFERENCES mcp_services (id) ON DELETE CASCADE,
    name               varchar(64)  NOT NULL,
    description        varchar(200),
    priority           int          NOT NULL DEFAULT 1000
                       CHECK (priority BETWEEN 0 AND 65535),
    path_mode          varchar(8)
                       CHECK (path_mode IN ('EXACT', 'PREFIX', 'REGEX')),
    path_value         varchar(256),
    host_mode          varchar(8)
                       CHECK (host_mode IN ('EXACT', 'PREFIX', 'REGEX')),
    host_value         varchar(256),
    methods            varchar(64),
    header_conditions  jsonb        NOT NULL DEFAULT '[]'::jsonb,
    status             varchar(16)  NOT NULL DEFAULT 'ENABLED'
                       CHECK (status IN ('ENABLED', 'DISABLED')),
    version            bigint       NOT NULL DEFAULT 0,
    created_by         uuid         REFERENCES users (id) ON DELETE RESTRICT,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_mcp_route_path_pair CHECK (
        (path_mode IS NULL AND path_value IS NULL)
        OR (path_mode IS NOT NULL AND path_value IS NOT NULL
            AND (path_mode = 'REGEX' OR path_value LIKE '/%'))),
    CONSTRAINT chk_mcp_route_host_pair CHECK (
        (host_mode IS NULL AND host_value IS NULL)
        OR (host_mode IS NOT NULL AND host_value IS NOT NULL))
);

CREATE UNIQUE INDEX uq_mcp_route_rule_service_name ON mcp_route_rule (tenant_id, mcp_service_id, name);
CREATE INDEX idx_mcp_route_rule_service ON mcp_route_rule (tenant_id, mcp_service_id, priority DESC);

-- Backfill the immutable default catch-all for services that already exist
-- (deterministic ids keep the migration idempotent by construction).
INSERT INTO mcp_route_rule
    (id, tenant_id, mcp_service_id, name, description, priority, path_mode, path_value,
     host_mode, host_value, methods, header_conditions, status, version, created_by, created_at, updated_at)
SELECT md5(tenant_id::text || id::text)::uuid, tenant_id, id, 'default', NULL, 0,
       NULL, NULL, NULL, NULL, NULL, '[]'::jsonb, 'ENABLED', 0, NULL, now(), now()
FROM mcp_services;
