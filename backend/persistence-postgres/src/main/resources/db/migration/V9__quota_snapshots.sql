-- V9: quota_snapshots (G4.2)
--
-- Append-only history of Plan/quota status per subscription. Rows are scoped
-- to one of three levels: the subscription itself (shared pool / aggregate),
-- a seat, or a credential (member key). `source` records the authority level
-- from provider-adapter-contract §6 — OFFICIAL_API (adapter balance fetch),
-- LOCAL_ESTIMATE (local usage vs. admin-recorded quota), or UNAVAILABLE
-- (no official API; never invented values). `provider_status_json` is reserved
-- for sanitized provider payloads and is never populated with secrets.

CREATE TABLE quota_snapshots (
    id                   uuid         PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    subscription_id      uuid         NOT NULL REFERENCES upstream_subscriptions (id) ON DELETE CASCADE,
    seat_id              uuid         REFERENCES plan_seats (id) ON DELETE CASCADE,
    credential_id        uuid         REFERENCES upstream_credentials (id) ON DELETE CASCADE,
    window_type          varchar(16)  NOT NULL DEFAULT 'UNKNOWN'
                         CHECK (window_type IN ('PERIOD', 'ROLLING_5H', 'WEEKLY', 'MONTHLY', 'UNKNOWN')),
    total                numeric(24,10),
    used                 numeric(24,10),
    remaining            numeric(24,10),
    unit                 varchar(16)  NOT NULL DEFAULT 'UNKNOWN'
                         CHECK (unit IN ('POINTS', 'TOKENS', 'REQUESTS', 'CURRENCY', 'UNKNOWN')),
    shared_pool          boolean      NOT NULL DEFAULT FALSE,
    source               varchar(16)  NOT NULL
                         CHECK (source IN ('OFFICIAL_API', 'LOCAL_ESTIMATE', 'UNAVAILABLE')),
    provider_status_json jsonb,
    synced_at            timestamptz  NOT NULL,
    error_message        varchar(500),
    created_at           timestamptz  NOT NULL DEFAULT now()
);

-- Latest-first reads per subscription and per credential.
CREATE INDEX idx_quota_snapshots_subscription
    ON quota_snapshots (tenant_id, subscription_id, synced_at DESC);
CREATE INDEX idx_quota_snapshots_credential
    ON quota_snapshots (tenant_id, credential_id, synced_at DESC);
