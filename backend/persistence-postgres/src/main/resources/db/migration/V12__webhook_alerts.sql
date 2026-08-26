-- V12: webhook_endpoints + alert_rules + alert_events + webhook_delivery_attempts (G4.5)
--
-- Alerting: endpoints receive HMAC-SHA256-signed JSON events; rules define
-- metric thresholds with a dedupe window; events are deduplicated per
-- (rule, dedupe key) and delivered with retries (bounded attempts, exponential
-- backoff). The signing secret is stored AES-GCM-encrypted (AAD tenant +
-- endpoint), never in plaintext.

CREATE TABLE webhook_endpoints (
    id                  uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name                varchar(200) NOT NULL,
    url                 varchar(500) NOT NULL,
    secret_encrypted    bytea        NOT NULL,
    secret_nonce        bytea        NOT NULL,
    secret_key_version  varchar(32)  NOT NULL,
    enabled             boolean      NOT NULL DEFAULT TRUE,
    timeout_ms          integer      NOT NULL DEFAULT 5000,
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE alert_rules (
    id                  uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name                varchar(200) NOT NULL,
    type                varchar(32)  NOT NULL
                        CHECK (type IN ('USAGE_MISSING_RATE', 'UPSTREAM_ERROR_RATE', 'BALANCE_UNAVAILABLE',
                                        'USAGE_SURGE')),
    scope_json          jsonb,
    threshold           numeric(12,6) NOT NULL,
    dedupe_minutes      integer      NOT NULL DEFAULT 60,
    enabled             boolean      NOT NULL DEFAULT TRUE,
    webhook_endpoint_id uuid         REFERENCES webhook_endpoints (id) ON DELETE SET NULL,
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE alert_events (
    id                  uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    rule_id             uuid         NOT NULL REFERENCES alert_rules (id) ON DELETE CASCADE,
    dedupe_key          varchar(200) NOT NULL,
    occurred_at         timestamptz  NOT NULL,
    value               numeric(12,6),
    status              varchar(16)  NOT NULL DEFAULT 'FIRED'
                        CHECK (status IN ('FIRED', 'DEDUPED')),
    payload_json        jsonb,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, rule_id, dedupe_key)
);

CREATE TABLE webhook_delivery_attempts (
    id                  uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    event_id            uuid         NOT NULL REFERENCES alert_events (id) ON DELETE CASCADE,
    endpoint_id         uuid         NOT NULL REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    attempt             integer      NOT NULL DEFAULT 1,
    http_status         integer,
    next_retry_at       timestamptz,
    error_message       varchar(500),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (event_id, endpoint_id, attempt)
);

CREATE INDEX idx_alert_events_tenant ON alert_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_deliveries_retry ON webhook_delivery_attempts (tenant_id, next_retry_at)
    WHERE next_retry_at IS NOT NULL;
