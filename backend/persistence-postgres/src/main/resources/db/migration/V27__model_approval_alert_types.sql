-- ============================================================================
-- 27. Event-driven model-approval notification rule types (F03)
--     alert_rules CHECK must admit the three approval transitions. These types
--     are not periodically evaluated — the approval workflow fires them
--     immediately (value = 1 per occurrence); delivery/signing/retry reuse the
--     alert machinery unchanged.
-- ============================================================================
ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_type_check;
ALTER TABLE alert_rules
    ADD CONSTRAINT alert_rules_type_check
    CHECK (type IN ('USAGE_MISSING_RATE', 'UPSTREAM_ERROR_RATE', 'BALANCE_UNAVAILABLE', 'USAGE_SURGE',
                    'BUDGET_THRESHOLD', 'QUOTA_THRESHOLD',
                    'MODEL_APPROVAL_SUBMITTED', 'MODEL_APPROVAL_APPROVED', 'MODEL_APPROVAL_REJECTED'));
