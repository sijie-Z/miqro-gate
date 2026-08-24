-- ============================================================================
-- V5: L2 cache entries and manual price snapshots.
--
-- The L2 cache is PostgreSQL-backed, DISABLED by default (see ADR-0008):
-- only virtual keys with cache_policy=ENABLED may participate, and only when
-- the gateway's cache subsystem is explicitly enabled.
--
-- price_snapshot stores manual/estimated/official unit prices per million
-- tokens. It is intentionally NOT tenant-scoped: prices belong to the global
-- provider product catalog.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. cache_entry: raw byte response cache, one row per normalized request
-- ---------------------------------------------------------------------------
CREATE TABLE cache_entry (
    id                      uuid         PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    cache_key               bytea        NOT NULL,           -- SHA-256 of the normalized cache key
    virtual_key_id          uuid         NOT NULL,
    project_id              uuid         NOT NULL,
    provider_product_id     uuid         NOT NULL,
    model_id                varchar(128) NOT NULL,
    provider_request_id     varchar(128),
    status_code             integer      NOT NULL,
    content_type            varchar(128),
    response_headers        jsonb        NOT NULL DEFAULT '{}',
    body                    bytea        NOT NULL,           -- raw response bytes (SSE replayed byte-identically)
    meta_json               jsonb        NOT NULL DEFAULT '{}',
    hit_count_l1            bigint       NOT NULL DEFAULT 0,  -- L1 hits (in-memory cache of this entry)
    hit_count_l2            bigint       NOT NULL DEFAULT 0,  -- L2 hits (served from this row)
    expires_at              timestamptz,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_cache_entry_virtual_key
        FOREIGN KEY (tenant_id, virtual_key_id) REFERENCES virtual_keys (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_cache_entry_project
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_cache_entry_product
        FOREIGN KEY (provider_product_id) REFERENCES provider_products (id) ON DELETE RESTRICT
);

-- One cached response per normalized request per tenant.
CREATE UNIQUE INDEX uq_cache_entry_tenant_key ON cache_entry (tenant_id, cache_key);
CREATE INDEX idx_cache_entry_project_id ON cache_entry (project_id);
CREATE INDEX idx_cache_entry_expires_at ON cache_entry (expires_at);
CREATE INDEX idx_cache_entry_tenant_id ON cache_entry (tenant_id);

-- ---------------------------------------------------------------------------
-- 2. price_snapshot: unit prices per million tokens
-- ---------------------------------------------------------------------------
CREATE TABLE price_snapshot (
    id                   uuid          PRIMARY KEY,
    provider_product_id  uuid          NOT NULL REFERENCES provider_products (id) ON DELETE RESTRICT,
    model_id             varchar(128)  NOT NULL,
    token_type           varchar(32)   NOT NULL
                         CHECK (token_type IN ('INPUT', 'OUTPUT', 'CACHE_READ', 'CACHE_CREATION')),
    currency             varchar(3)    NOT NULL DEFAULT 'CNY',
    unit_price           numeric(24,10) NOT NULL,            -- price per 1,000,000 tokens
    effective_from       timestamptz   NOT NULL DEFAULT now(),
    source               varchar(32)   NOT NULL DEFAULT 'MANUAL'
                         CHECK (source IN ('MANUAL', 'OFFICIAL', 'ESTIMATED')),
    created_by           uuid,
    created_at           timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_price_snapshot_lookup
    ON price_snapshot (provider_product_id, model_id, token_type, effective_from DESC);
