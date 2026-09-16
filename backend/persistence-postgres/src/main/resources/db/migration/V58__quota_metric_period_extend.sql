-- ============================================================================
-- 58. quota metric/period extension (#683): Tencent quota-management
--     alignment — the COST metric (priced upstream cost of the window) and the
--     YEARLY (UTC calendar year) period join the original enumerations on both
--     the per-scope rules and the default template. The watermark stays
--     read-time; the tables still store only the plan.
-- ============================================================================
ALTER TABLE quota_rules DROP CONSTRAINT quota_rules_metric_check;
ALTER TABLE quota_rules DROP CONSTRAINT quota_rules_period_check;

ALTER TABLE quota_rules
    ADD CONSTRAINT quota_rules_metric_check CHECK (metric IN ('TOKENS', 'REQUESTS', 'COST'));
ALTER TABLE quota_rules
    ADD CONSTRAINT quota_rules_period_check CHECK (period IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'));

ALTER TABLE quota_default_template DROP CONSTRAINT quota_default_template_metric_check;
ALTER TABLE quota_default_template DROP CONSTRAINT quota_default_template_period_check;

ALTER TABLE quota_default_template
    ADD CONSTRAINT quota_default_template_metric_check CHECK (metric IN ('TOKENS', 'REQUESTS', 'COST'));
ALTER TABLE quota_default_template
    ADD CONSTRAINT quota_default_template_period_check CHECK (period IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'));
