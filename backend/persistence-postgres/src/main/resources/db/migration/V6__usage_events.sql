-- ============================================================================
-- V6: Usage events (tiered statistics) and cache hit events.
--
-- usage_event is the single append-only fact table for tiered usage
-- statistics. Token columns are nullable: cache hits and coalesced requests
-- carry no usage (NULL), while UPSTREAM rows carry the observed usage.
--
-- Idempotency: provider_request_id (upstream request id, e.g. chatcmpl-...)
-- is unique per tenant; the gateway writes with INSERT ... ON CONFLICT DO
-- NOTHING so retried flushes never double-count. Cache hits have no upstream
-- request id (NULL) and therefore never conflict.
--
-- L1_HIT/L2_HIT rows are NOT written to usage_event; they are counted in
-- cache_hit_event (deduplicated per cache_key + level + second).
-- ============================================================================

CREATE TABLE usage_event (
    id                       uuid         PRIMARY KEY,
    tenant_id                uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    provider_request_id      varchar(128),
    virtual_key_id           uuid         NOT NULL,
    project_id               uuid         NOT NULL,
    provider_product_id      uuid         NOT NULL,
    credential_id            uuid,
    model_id                 varchar(128) NOT NULL,
    cache_level              varchar(32)  NOT NULL DEFAULT 'UPSTREAM'
                             CHECK (cache_level IN ('UPSTREAM', 'COALESCED', 'L1_HIT', 'L2_HIT')),
    input_tokens             bigint,
    output_tokens            bigint,
    cache_creation_input_tokens bigint,
    cache_read_input_tokens  bigint,
    prompt_tokens            bigint,
    completion_tokens        bigint,
    total_tokens             bigint,
    reasoning_tokens         bigint,
    latency_ms               bigint,
    upstream_status_code     integer,
    cache_key                bytea,
    is_complete              boolean      NOT NULL DEFAULT FALSE,
    usage_missing            boolean      NOT NULL DEFAULT FALSE,
    gateway_request_id       varchar(64)  NOT NULL,
    occurred_at              timestamptz  NOT NULL DEFAULT now(),
    created_at               timestamptz  NOT NULL DEFAULT now()
);

-- Idempotency target for the gateway's batch writer.
CREATE UNIQUE INDEX uq_usage_event_tenant_provider_request
    ON usage_event (tenant_id, provider_request_id)
    WHERE provider_request_id IS NOT NULL;

CREATE INDEX idx_usage_event_virtual_key_id ON usage_event (virtual_key_id);
CREATE INDEX idx_usage_event_project_id ON usage_event (project_id);
CREATE INDEX idx_usage_event_product_id ON usage_event (provider_product_id);
CREATE INDEX idx_usage_event_cache_level ON usage_event (cache_level);
CREATE INDEX idx_usage_event_occurred_at ON usage_event (occurred_at);
CREATE INDEX idx_usage_event_tenant_id ON usage_event (tenant_id);

-- ---------------------------------------------------------------------------
-- Cache hit counting (deduplicated per cache_key + level + second)
-- ---------------------------------------------------------------------------
CREATE TABLE cache_hit_event (
    id                      uuid         PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    cache_key               bytea        NOT NULL,
    virtual_key_id          uuid         NOT NULL,
    project_id              uuid         NOT NULL,
    provider_product_id     uuid         NOT NULL,
    level                   varchar(32)  NOT NULL
                            CHECK (level IN ('L1_HIT', 'L2_HIT')),
    occurred_at             timestamptz  NOT NULL DEFAULT now(),
    gateway_request_id      varchar(64)  NOT NULL,
    created_at              timestamptz  NOT NULL DEFAULT now()
);

-- Dedup window: one hit record per (cache_key, level, second).
CREATE UNIQUE INDEX uq_cache_hit_event_dedup
    ON cache_hit_event (tenant_id, cache_key, level, occurred_at);

CREATE INDEX idx_cache_hit_event_project_id ON cache_hit_event (project_id);
CREATE INDEX idx_cache_hit_event_occurred_at ON cache_hit_event (occurred_at);
CREATE INDEX idx_cache_hit_event_tenant_id ON cache_hit_event (tenant_id);
