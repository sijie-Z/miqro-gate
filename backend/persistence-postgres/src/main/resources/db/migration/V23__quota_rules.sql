-- ============================================================================
-- 23. quota_rules: usage quota plans (alerting-only, never blocking)
--     Tencent/Aliyun consumer-quota alignment: metric (TOKENS | REQUESTS) x
--     period (DAILY | WEEKLY | MONTHLY) x scope (USER | PROJECT) with a warn
--     threshold percent. The watermark is computed at read time from usage
--     events, so the table stores only the plan. One rule per
--     (tenant, scope, metric, period).
-- ============================================================================
CREATE TABLE quota_rules (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    scope_type    varchar(16)  NOT NULL CHECK (scope_type IN ('USER', 'PROJECT')),
    scope_id      uuid         NOT NULL,
    metric        varchar(16)  NOT NULL CHECK (metric IN ('TOKENS', 'REQUESTS')),
    period        varchar(16)  NOT NULL CHECK (period IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    limit_value   bigint       NOT NULL CHECK (limit_value > 0),
    warn_percent  int          NOT NULL DEFAULT 80 CHECK (warn_percent BETWEEN 1 AND 99),
    status        varchar(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by    uuid         NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_quota_rule_tenant_creator
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_quota_rule UNIQUE (tenant_id, scope_type, scope_id, metric, period)
);

CREATE INDEX idx_quota_rule_scope ON quota_rules (tenant_id, scope_type, scope_id, status);
