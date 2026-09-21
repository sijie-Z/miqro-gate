-- ============================================================================
-- V51: Retention log (ADR-0014 §8 增补, 2026-09-15).
--
-- Console-side ledger filled by the OPTIONAL built-in Kafka consumer
-- (miqrokey.retention.consumer.*): one row per retention envelope, in the
-- exact encrypted form shipped by the gateway — ciphertext/nonce stay bytea,
-- plaintext exists only inside the control plane's decryption path for the
-- authorized (admin) reader. event_id is the idempotency key: replays of the
-- at-least-once stream are no-ops.
-- ============================================================================

CREATE TABLE retention_log (
    event_id                uuid         PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    user_id                 uuid         NOT NULL,
    virtual_key_id          uuid         NOT NULL,
    wire_protocol           varchar(32)  NOT NULL,
    direction               varchar(16)  NOT NULL DEFAULT 'INPUT'
                            CHECK (direction IN ('INPUT', 'OUTPUT')),
    gateway_request_id      varchar(64)  NOT NULL,
    occurred_at             timestamptz  NOT NULL,
    key_version             varchar(16)  NOT NULL,
    ciphertext              bytea        NOT NULL,
    nonce                   bytea        NOT NULL,
    text_char_count         integer      NOT NULL DEFAULT 0,
    truncated               boolean      NOT NULL DEFAULT FALSE,
    created_at              timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_retention_log_tenant_time ON retention_log (tenant_id, occurred_at DESC);
CREATE INDEX idx_retention_log_tenant_user ON retention_log (tenant_id, user_id, occurred_at DESC);
CREATE INDEX idx_retention_log_gateway_request ON retention_log (gateway_request_id);
