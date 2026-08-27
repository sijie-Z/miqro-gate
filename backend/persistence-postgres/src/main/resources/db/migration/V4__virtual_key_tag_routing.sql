-- ============================================================================
-- V4: Virtual Key label routing, key-project binding, and model approvals.
--
-- Implements the "Virtual Key 标签路由" architecture (see ADR-0008):
--   mqk_live_<publicKeyId>_<secret>.<projectTag>
-- The label is carried in plaintext on the key for routing only; the
-- authorization authority is key_project_binding (and virtual_key_models).
-- The HMAC digest is unchanged and does NOT include the label.
--
-- Conventions follow V1 (uuid PK, timestamptz UTC, version for optimistic
-- locking, tenant-scoped tables carry tenant_id).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. virtual_keys: per-key cache policy (explicit opt-in only, default OFF)
-- ---------------------------------------------------------------------------
ALTER TABLE virtual_keys
    ADD COLUMN cache_policy varchar(32) NOT NULL DEFAULT 'DISABLED'
        CHECK (cache_policy IN ('DISABLED', 'ENABLED'));

-- ---------------------------------------------------------------------------
-- 2. projects: unique routing label (per tenant, nullable until assigned)
-- ---------------------------------------------------------------------------
ALTER TABLE projects
    ADD COLUMN project_tag varchar(64);

-- Labels are embedded in keys, so restrict to a safe, unambiguous alphabet.
ALTER TABLE projects
    ADD CONSTRAINT chk_projects_project_tag_format
        CHECK (project_tag IS NULL OR project_tag ~ '^[A-Za-z0-9_-]{1,64}$');

CREATE UNIQUE INDEX uq_projects_tenant_project_tag
    ON projects (tenant_id, project_tag)
    WHERE project_tag IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. key_project_binding: authorization authority for label routing
-- ---------------------------------------------------------------------------
-- A virtual key is bound to exactly one project (its routing target). The
-- binding is separate from virtual_keys.project_id so that binding state can
-- evolve (e.g., disable) without rewriting the key row, and so the gateway
-- snapshot can load routing decisions in one pass.
CREATE TABLE key_project_binding (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    virtual_key_id uuid      NOT NULL,
    project_id  uuid         NOT NULL,
    status      varchar(32)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'DISABLED')),
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_key_project_binding_key
        FOREIGN KEY (tenant_id, virtual_key_id) REFERENCES virtual_keys (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_key_project_binding_project
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_key_project_binding_key_project
    ON key_project_binding (virtual_key_id, project_id);
CREATE INDEX idx_key_project_binding_project_id ON key_project_binding (project_id);
CREATE INDEX idx_key_project_binding_tenant_id ON key_project_binding (tenant_id);

-- ---------------------------------------------------------------------------
-- 4. model_approval: workflow for granting additional models to a key
-- ---------------------------------------------------------------------------
CREATE TABLE model_approval (
    id              uuid         PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    virtual_key_id  uuid         NOT NULL,
    model_id        varchar(128) NOT NULL,
    requested_by    uuid         NOT NULL,
    status          varchar(32)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by     uuid,
    review_note     varchar(500),
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_model_approval_key
        FOREIGN KEY (tenant_id, virtual_key_id) REFERENCES virtual_keys (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_approval_requested_by
        FOREIGN KEY (tenant_id, requested_by) REFERENCES users (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_model_approval_key_id ON model_approval (virtual_key_id);
CREATE INDEX idx_model_approval_status ON model_approval (status);
CREATE INDEX idx_model_approval_tenant_id ON model_approval (tenant_id);
