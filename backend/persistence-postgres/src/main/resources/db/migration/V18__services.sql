-- V18: internal services registry (P3.2)
-- Managed internal services (platform components, MCP endpoints) registered
-- for gateway integration. Base URLs are admin-configured and must be https
-- without userinfo, mirroring the upstream target rules.

CREATE TABLE services (
    id          uuid          PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name        varchar(200)  NOT NULL,
    kind        varchar(16)   NOT NULL DEFAULT 'HTTP'
                CHECK (kind IN ('HTTP', 'MCP', 'OTHER')),
    description varchar(2000),
    base_url    varchar(2048) NOT NULL,
    status      varchar(16)   NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'DISABLED')),
    version     bigint        NOT NULL DEFAULT 0,
    created_by  uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_services_tenant_name ON services (tenant_id, name);
