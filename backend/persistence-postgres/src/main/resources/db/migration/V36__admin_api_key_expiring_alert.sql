-- ============================================================================
-- 36. Event-driven ADMIN_API_KEY_EXPIRING alert type (F60 batch 3 follow-up)
--     The daily notifier fires one event per expiring key (default OFF: no rule
--     of this type exists unless an admin creates one). alert_rules CHECK must
--     admit the new type.
-- ============================================================================
ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_type_check;
ALTER TABLE alert_rules
    ADD CONSTRAINT alert_rules_type_check
    CHECK (type IN ('USAGE_MISSING_RATE', 'UPSTREAM_ERROR_RATE', 'BALANCE_UNAVAILABLE', 'USAGE_SURGE',
                    'BUDGET_THRESHOLD', 'QUOTA_THRESHOLD',
                    'MODEL_APPROVAL_SUBMITTED', 'MODEL_APPROVAL_APPROVED', 'MODEL_APPROVAL_REJECTED',
                    'ADMIN_API_KEY_EXPIRING'));
