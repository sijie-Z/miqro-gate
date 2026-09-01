-- V15: BUDGET_THRESHOLD alert type (G8.3)
-- Project budget watermark alerts reuse the alert_rules machinery; the
-- CHECK constraint must admit the new type.

ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_type_check;

ALTER TABLE alert_rules
    ADD CONSTRAINT alert_rules_type_check
    CHECK (type IN ('USAGE_MISSING_RATE', 'UPSTREAM_ERROR_RATE', 'BALANCE_UNAVAILABLE', 'USAGE_SURGE',
                    'BUDGET_THRESHOLD'));
