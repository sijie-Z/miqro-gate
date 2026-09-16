-- ============================================================================
-- 59. quota soft-landing enforcement (#684 block ①): quota governance stops
--     being alerting-only. A rule may opt into REJECT (default stays ALERT, so
--     every existing row keeps its current behaviour), and the control-plane
--     evaluator materialises the scopes that have exhausted their period into
--     `quota_enforcement` — at most one row per (tenant, scope_type, scope_id),
--     carrying the exceeded-watermark snapshot the gateway reports back.
--
--     The row is a projection of the rule state, not a second source of truth:
--     it is rewritten when the blocking rule or the window changes, and removed
--     as soon as the limit is raised back above usage, the rule is disabled or
--     deleted (FK CASCADE), or the period rolls over. `window_to` doubles as the
--     fail-open deadline — the snapshot loader only picks up rows that are still
--     inside their window.
--
--     Nothing here deletes or invalidates a virtual key: soft landing rejects
--     the request and leaves the credential intact.
-- ============================================================================
ALTER TABLE quota_rules ADD COLUMN enforcement varchar(16) NOT NULL DEFAULT 'ALERT';

ALTER TABLE quota_rules
    ADD CONSTRAINT quota_rules_enforcement_check CHECK (enforcement IN ('ALERT', 'REJECT'));

CREATE TABLE quota_enforcement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    scope_type varchar(16) NOT NULL CHECK (scope_type IN ('USER', 'PROJECT')),
    scope_id uuid NOT NULL,
    rule_id uuid NOT NULL REFERENCES quota_rules (id) ON DELETE CASCADE,
    metric varchar(16) NOT NULL CHECK (metric IN ('TOKENS', 'REQUESTS', 'COST')),
    period varchar(16) NOT NULL CHECK (period IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    limit_value bigint NOT NULL CHECK (limit_value > 0),
    used_value numeric(24, 6) NOT NULL CHECK (used_value >= 0),
    used_percent numeric(9, 2) NOT NULL,
    window_from timestamptz NOT NULL,
    window_to timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_quota_enforcement_scope UNIQUE (tenant_id, scope_type, scope_id),
    CONSTRAINT ck_quota_enforcement_window CHECK (window_to > window_from)
);

-- The evaluator reconciles one tenant at a time and falls back to the rule when
-- a scope row has to be explained.
CREATE INDEX idx_quota_enforcement_tenant ON quota_enforcement (tenant_id, scope_type, scope_id);
CREATE INDEX idx_quota_enforcement_rule ON quota_enforcement (rule_id);
-- Fail-open sweep: rows whose window has closed stop blocking on load.
CREATE INDEX idx_quota_enforcement_window_to ON quota_enforcement (window_to);
