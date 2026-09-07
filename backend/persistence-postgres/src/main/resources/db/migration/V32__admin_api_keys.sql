-- V32: open admin API keys (ADR-0015, batch 1).
-- Machine credentials for the programmable admin surface (/api/v1/admin-api).
-- Plaintext appears once at issue; only the SHA-256 digest is stored.

CREATE TABLE admin_api_keys (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name        varchar(200) NOT NULL,
    key_digest  bytea        NOT NULL,
    key_prefix  varchar(24)  NOT NULL,
    created_by  uuid         REFERENCES users (id),
    expires_at  timestamptz,
    revoked_at  timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_admin_api_keys_tenant_name ON admin_api_keys (tenant_id, name);
CREATE INDEX idx_admin_api_keys_digest ON admin_api_keys (key_digest);
