-- V21: MCP tools (P3.5, Tencent AI gateway Tools management)
-- Tools registered under an MCP service; enabled/disabled individually
-- (Tencent Tools 管理 semantics). The tool name is the unique identifier AI
-- agents use to invoke it.

CREATE TABLE mcp_tools (
    id              uuid          PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    mcp_service_id  uuid          NOT NULL REFERENCES mcp_services (id) ON DELETE CASCADE,
    tool_name       varchar(128)  NOT NULL,
    description     varchar(2000),
    method          varchar(16)   NOT NULL DEFAULT 'GET'
                    CHECK (method IN ('GET', 'POST', 'PUT', 'DELETE', 'PATCH')),
    path            varchar(512)  NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'ENABLED'
                    CHECK (status IN ('ENABLED', 'DISABLED')),
    version         bigint        NOT NULL DEFAULT 0,
    created_by      uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_mcp_tools_service_name ON mcp_tools (tenant_id, mcp_service_id, tool_name);
CREATE INDEX idx_mcp_tools_service ON mcp_tools (mcp_service_id);
