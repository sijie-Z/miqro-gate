-- ============================================================================
-- 26. quota_default_template: global default quota strategy (Tencent doc 135489)
--     One row per tenant. When enabled, every newly created user receives a
--     snapshot copy as an ordinary quota_rules row (USER scope) — later edits
--     to the template never touch already-assigned rules, and disabling keeps
--     the copies. Manual rules always take precedence (the snapshot insert is
--     ON CONFLICT DO NOTHING).
-- ============================================================================
CREATE TABLE quota_default_template (
    tenant_id    uuid         PRIMARY KEY REFERENCES tenants (id) ON DELETE RESTRICT,
    enabled      boolean      NOT NULL DEFAULT FALSE,
    metric       varchar(16)  NOT NULL CHECK (metric IN ('TOKENS', 'REQUESTS')),
    period       varchar(16)  NOT NULL CHECK (period IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    limit_value  bigint       NOT NULL CHECK (limit_value > 0),
    updated_by   uuid         NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_quota_default_template_updater
        FOREIGN KEY (tenant_id, updated_by) REFERENCES users (tenant_id, id) ON DELETE RESTRICT
);
