-- V19: global configuration entries (P3.3)
-- Gateway-side configuration center: grouped key-value entries managed by
-- admins with optimistic locking. Non-secret configuration only — secrets
-- stay in the env/encrypted-secret pipeline, never here.

CREATE TABLE config_entries (
    id          uuid          PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    group_name  varchar(64)   NOT NULL,
    key         varchar(128)  NOT NULL,
    value       text          NOT NULL,
    description varchar(500),
    updated_by  uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    version     bigint        NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_config_entries_group_key ON config_entries (tenant_id, group_name, key);
