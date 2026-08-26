-- V11: export_tasks + usage_deletions (G4.4)
--
-- Admin export tasks (async CSV/JSONL gzip) and double-confirmed usage
-- deletions. Both are audit-relevant: exports carry a SHA-256 of the
-- generated artifact; deletions are permanent and must be confirmed with a
-- one-time token before rows are removed.

CREATE TABLE export_tasks (
    id                  uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    created_by          uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    format              varchar(8)   NOT NULL CHECK (format IN ('CSV', 'JSONL')),
    period_from         timestamptz  NOT NULL,
    period_to           timestamptz  NOT NULL,
    status              varchar(16)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    sha256              varchar(64),
    row_count           bigint,
    byte_count          bigint,
    file_bytes          bytea,
    error_message       varchar(500),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    finished_at         timestamptz,
    expires_at          timestamptz
);

CREATE INDEX idx_export_tasks_tenant ON export_tasks (tenant_id, created_at DESC);

CREATE TABLE usage_deletions (
    id                  uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    requested_by        uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    period_from         timestamptz  NOT NULL,
    period_to           timestamptz  NOT NULL,
    preview_count       bigint       NOT NULL,
    confirm_token_hash  bytea        NOT NULL,
    status              varchar(24)  NOT NULL DEFAULT 'PENDING_CONFIRMATION'
                        CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'EXECUTED', 'EXPIRED')),
    deleted_count       bigint,
    executed_at         timestamptz,
    expires_at          timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_deletions_tenant ON usage_deletions (tenant_id, created_at DESC);
