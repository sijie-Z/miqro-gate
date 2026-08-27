-- ============================================================================
-- V7: Model catalog, model access rules, budgets, and model-level budgets.
--
-- These tables support future portal features (catalog browsing, budget
-- alerts). No application code consumes them yet; they are created so the
-- schema matches the v1.0 detailed design document.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. model_catalog: global (non-tenant) model registry
-- ---------------------------------------------------------------------------
CREATE TABLE model_catalog (
    id                 uuid          PRIMARY KEY,
    provider_product_id uuid         NOT NULL REFERENCES provider_products (id) ON DELETE RESTRICT,
    model_id           varchar(128)  NOT NULL,
    display_name       varchar(200),
    context_window     integer,
    max_output_tokens  integer,
    status             varchar(32)   NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'DISABLED', 'DEPRECATED')),
    version            bigint        NOT NULL DEFAULT 0,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_model_catalog_product_model
    ON model_catalog (provider_product_id, model_id);

-- ---------------------------------------------------------------------------
-- 2. model_access: which tenant/project may use which catalog model
-- ---------------------------------------------------------------------------
CREATE TABLE model_access (
    id                 uuid          PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id         uuid          NOT NULL,
    model_id           varchar(128)  NOT NULL,
    status             varchar(32)   NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by         uuid,
    version            bigint        NOT NULL DEFAULT 0,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_model_access_project
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_model_access_project_model
    ON model_access (tenant_id, project_id, model_id);

-- ---------------------------------------------------------------------------
-- 3. budget: monthly budget per project (alerting only, never blocking)
-- ---------------------------------------------------------------------------
CREATE TABLE budget (
    id                 uuid          PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id         uuid          NOT NULL,
    period_month       varchar(7)    NOT NULL,               -- 'YYYY-MM'
    amount             numeric(24,10) NOT NULL,
    currency           varchar(3)    NOT NULL DEFAULT 'CNY',
    alert_threshold_pct numeric(5,2) NOT NULL DEFAULT 80.00,
    status             varchar(32)   NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'PAUSED')),
    version            bigint        NOT NULL DEFAULT 0,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_budget_project
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_budget_project_month
    ON budget (tenant_id, project_id, period_month);

-- ---------------------------------------------------------------------------
-- 4. model_budget: optional per-model cost ceiling
-- ---------------------------------------------------------------------------
CREATE TABLE model_budget (
    id                 uuid          PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id         uuid          NOT NULL,
    model_id           varchar(128)  NOT NULL,
    period_month       varchar(7)    NOT NULL,
    amount             numeric(24,10) NOT NULL,
    currency           varchar(3)    NOT NULL DEFAULT 'CNY',
    status             varchar(32)   NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'PAUSED')),
    version            bigint        NOT NULL DEFAULT 0,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_model_budget_project
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_model_budget_project_model_month
    ON model_budget (tenant_id, project_id, model_id, period_month);
