-- ============================================================================
-- 59. quota soft landing (#684, ADR-0020): per-rule exceeded action + the
--     enforcement verdicts consumed by the route snapshot. V58 already widened
--     the metric/period enumerations (COST / YEARLY).
--
--     quota_enforcement holds one row per ACTIVE REJECT rule whose current
--     window watermark is EXCEEDED; the control-plane evaluator replaces the
--     whole set on each cycle and publishes a route refresh when it changes,
--     so the gateway only ever reads the resulting scope->window-end map from
--     its in-memory snapshot (zero hot-path queries, no counters in the data
--     plane). Raising the limit or rolling into a new window clears the row
--     and traffic resumes without touching the key. window_end is the rule's
--     current window end at evaluation time: it feeds the 429's Retry-After
--     (earliest window end wins when several rules block one scope).
-- ============================================================================
ALTER TABLE quota_rules ADD COLUMN action varchar(16) NOT NULL DEFAULT 'ALERT'
    CONSTRAINT quota_rules_action_check CHECK (action IN ('ALERT', 'REJECT'));

CREATE TABLE quota_enforcement (
    rule_id     uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    scope_type  varchar(16)  NOT NULL CHECK (scope_type IN ('USER', 'PROJECT')),
    scope_id    uuid         NOT NULL,
    metric      varchar(16)  NOT NULL,
    period      varchar(16)  NOT NULL,
    window_end  timestamptz  NOT NULL,
    blocked_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_quota_enforcement_scope ON quota_enforcement (scope_type, scope_id);
