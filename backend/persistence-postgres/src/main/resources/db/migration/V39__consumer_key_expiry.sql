-- ============================================================================
-- 39. Consumer key expiry (issue #322, mirror of the admin-key lifecycle):
--     api_consumers gains an optional expires_at (NULL = never expires,
--     existing rows unchanged), and alert_rules admits the event-driven
--     CONSUMER_KEY_EXPIRING type (default OFF: no rule of this type exists
--     unless an admin creates one, same opt-in semantics as V36).
-- ============================================================================
ALTER TABLE api_consumers ADD COLUMN expires_at timestamptz;

ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_type_check;
ALTER TABLE alert_rules
    ADD CONSTRAINT alert_rules_type_check
    CHECK (type IN ('USAGE_MISSING_RATE', 'UPSTREAM_ERROR_RATE', 'BALANCE_UNAVAILABLE', 'USAGE_SURGE',
                    'BUDGET_THRESHOLD', 'QUOTA_THRESHOLD',
                    'MODEL_APPROVAL_SUBMITTED', 'MODEL_APPROVAL_APPROVED', 'MODEL_APPROVAL_REJECTED',
                    'ADMIN_API_KEY_EXPIRING', 'CONSUMER_KEY_EXPIRING'));
