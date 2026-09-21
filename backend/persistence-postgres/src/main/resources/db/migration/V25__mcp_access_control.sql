-- ============================================================================
-- 25. MCP 两级访问控制 (Tencent AI gateway doc 134890)
--     Server-level ACL (NONE/ALLOW/DENY + consumer list) and Tool-level
--     refinement on top of it ("who may call the whole server", "who may call
--     a specific tool"). Grants rows with tool_id NULL are the server list;
--     non-NULL rows refine a single tool. Tool refinement is only allowed
--     while the server mode is NONE (全开放), mirroring the upstream doc.
-- ============================================================================
CREATE TABLE mcp_service_access (
    id             uuid          PRIMARY KEY,
    tenant_id      uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    mcp_service_id uuid          NOT NULL REFERENCES mcp_services (id) ON DELETE CASCADE,
    mode           varchar(8)    NOT NULL DEFAULT 'NONE'
                                 CHECK (mode IN ('NONE', 'ALLOW', 'DENY')),
    created_by     uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    version        bigint        NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_mcp_service_access UNIQUE (mcp_service_id)
);

CREATE INDEX idx_mcp_service_access_tenant ON mcp_service_access (tenant_id);

CREATE TABLE mcp_access_grants (
    id                uuid         PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    service_access_id uuid         NOT NULL REFERENCES mcp_service_access (id) ON DELETE CASCADE,
    tool_id           uuid         REFERENCES mcp_tools (id) ON DELETE CASCADE,
    consumer_id       uuid         NOT NULL REFERENCES api_consumers (id) ON DELETE CASCADE,
    mode              varchar(8)   NOT NULL CHECK (mode IN ('ALLOW', 'DENY')),
    created_by        uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    version           bigint       NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_mcp_access_grant UNIQUE (service_access_id, tool_id, consumer_id)
);

CREATE INDEX idx_mcp_access_grants_scope ON mcp_access_grants (service_access_id, tool_id);
CREATE INDEX idx_mcp_access_grants_tenant ON mcp_access_grants (tenant_id);
