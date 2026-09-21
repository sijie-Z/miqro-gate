-- V13: external-system API consumers (ADR-0010).
-- API keys are stored as SHA-256 digests only; plaintext appears once at
-- creation. The consumer channel protects /api/v1/billing/** for the
-- platform while the portal session channel stays unchanged.

CREATE TABLE api_consumers (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name        varchar(200) NOT NULL,
    key_digest  bytea       NOT NULL,
    key_prefix  varchar(8)  NOT NULL,
    status      varchar(32) NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'DISABLED')),
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_api_consumers_tenant_name ON api_consumers (tenant_id, name);
CREATE INDEX idx_api_consumers_key_digest ON api_consumers (key_digest);
