-- ============================================================================
-- V8: request_usage_records — per-request lifecycle audit trail.
--
-- One row per gateway request that passed authentication and was forwarded
-- upstream (cache hits are counted in cache_hit_event instead; auth/model
-- rejections are security events and never reach this table).
--
-- Lifecycle: the gateway writes the row as IN_FLIGHT at request start and
-- finalizes it exactly once at completion. The writer uses a guarded upsert
-- (UPDATE ... WHERE request_status = 'IN_FLIGHT'): a retried flush can never
-- double-finalize, and a finalized record's business fields are never
-- rewritten. Idempotency key: (started_at, gateway_request_id).
--
-- Partitioning: monthly range on started_at with a DEFAULT partition that
-- catches out-of-range rows. Monthly partition creation for retention
-- management is a later operations goal; the DEFAULT partition guarantees
-- inserts never fail on month boundaries in the meantime.
--
-- Deferred (G4.x usage dashboards): user/team/project name snapshots,
-- subscription/credential name snapshots, provider_usage_json, price catalog
-- version and snapshot, cost columns, plan window reference, quota. The
-- identity chain (tenant -> user -> key -> product -> provider -> credential)
-- is fully populated today; display names are joined at query time.
--
-- Body content (prompt, code, tool payloads, model answers) is NEVER written.
-- ============================================================================

CREATE TABLE request_usage_records (
    started_at                  timestamptz   NOT NULL,
    id                          uuid          NOT NULL,
    gateway_request_id          varchar(64)   NOT NULL,
    upstream_request_id         varchar(128),
    tenant_id                   uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    user_id                     uuid          NOT NULL,
    project_id                  uuid          NOT NULL,
    virtual_key_id              uuid          NOT NULL,
    provider_id                 uuid          NOT NULL,
    provider_product_id         uuid          NOT NULL,
    credential_id               uuid          NOT NULL,
    model_id                    varchar(128),
    wire_protocol               varchar(32)   NOT NULL,
    streaming                   boolean       NOT NULL DEFAULT FALSE,
    request_status              varchar(32)   NOT NULL
                                CHECK (request_status IN ('IN_FLIGHT', 'SUCCEEDED', 'UPSTREAM_REJECTED',
                                    'UPSTREAM_UNAVAILABLE', 'CLIENT_CANCELLED', 'TIMEOUT_BEFORE_FIRST_BYTE',
                                    'STREAM_INTERRUPTED', 'AUTH_REJECTED', 'MODEL_NOT_ALLOWED',
                                    'USAGE_PARSE_FAILED')),
    first_byte_at               timestamptz,
    completed_at                timestamptz,
    duration_ms                 bigint,
    time_to_first_byte_ms       bigint,
    http_status                 integer,
    client_cancelled            boolean       NOT NULL DEFAULT FALSE,
    partial_response            boolean       NOT NULL DEFAULT FALSE,
    retry_count                 integer       NOT NULL DEFAULT 0,
    input_tokens                bigint,
    output_tokens               bigint,
    cache_creation_input_tokens bigint,
    cache_read_input_tokens     bigint,
    prompt_tokens               bigint,
    completion_tokens           bigint,
    total_tokens                bigint,
    reasoning_tokens            bigint,
    usage_missing               boolean       NOT NULL DEFAULT FALSE,
    finalized_at                timestamptz,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (started_at, id),
    -- Idempotency target for the gateway's guarded upsert (finalize once).
    CONSTRAINT uq_request_usage_records_gateway UNIQUE (started_at, gateway_request_id)
) PARTITION BY RANGE (started_at);

-- Catches rows outside managed monthly partitions; never blocks inserts.
CREATE TABLE request_usage_records_default PARTITION OF request_usage_records DEFAULT;

CREATE INDEX idx_request_usage_records_tenant
    ON request_usage_records (tenant_id, started_at DESC);
CREATE INDEX idx_request_usage_records_project
    ON request_usage_records (project_id, started_at DESC);
CREATE INDEX idx_request_usage_records_virtual_key
    ON request_usage_records (virtual_key_id, started_at DESC);
CREATE INDEX idx_request_usage_records_product_model
    ON request_usage_records (provider_product_id, model_id, started_at DESC);
CREATE INDEX idx_request_usage_records_status
    ON request_usage_records (request_status)
    WHERE request_status <> 'SUCCEEDED';
