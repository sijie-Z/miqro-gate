-- ============================================================================
-- 71. Rate-signal alert types (ADR-0026 option D, issue #706).
--
-- Option D of the rate-limiting evaluation is "observe and alert, never
-- block": the control plane gains two queryable signals so that ADR-0026's §4
-- trigger conditions stop being unanswerable — with zero request-path work in
-- the gateway and no external state.
--
--   UPSTREAM_RATE_LIMITED  COUNT of upstream 429 answers in the rolling hour
--                          (upstream_status_code = 429). Deliberately a count,
--                          not a share: the share already exists inside
--                          UPSTREAM_ERROR_RATE, which mixes 429 with 5xx — and
--                          "upstream is throttling us" and "upstream is broken"
--                          are different incidents with different responses.
--                          Requests the gateway itself rejected for quota never
--                          reach upstream and carry no upstream status, so the
--                          gateway's own 429s are NOT counted here (see the
--                          runbook §14.3 attribution split).
--
--   KEY_REQUEST_RATE       peak per-key request count in the rolling hour
--                          (the highest COUNT(*) grouped by virtual_key_id).
--                          The tenant-wide USAGE_SURGE cannot say WHO is
--                          surging; a rule with threshold N alerts when one
--                          key exceeds N requests/hour, and the fired event's
--                          payload_json carries that key's id and name.
--
-- Both are evaluated in the control plane by AlertEvaluator. ADR-0026 §6 fixes
-- that constraint explicitly: no request-path counting, no external state, and
-- no high-cardinality metric labels — the per-key dimension lives in the SQL
-- aggregate, not in a Prometheus label.
-- ============================================================================

ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_type_check;
ALTER TABLE alert_rules
    ADD CONSTRAINT alert_rules_type_check
    CHECK (type IN ('USAGE_MISSING_RATE', 'UPSTREAM_ERROR_RATE', 'BALANCE_UNAVAILABLE', 'USAGE_SURGE',
                    'BUDGET_THRESHOLD', 'QUOTA_THRESHOLD',
                    'MODEL_APPROVAL_SUBMITTED', 'MODEL_APPROVAL_APPROVED', 'MODEL_APPROVAL_REJECTED',
                    'ADMIN_API_KEY_EXPIRING', 'CONSUMER_KEY_EXPIRING',
                    'USAGE_QUEUE_SATURATION',
                    'UPSTREAM_RATE_LIMITED', 'KEY_REQUEST_RATE'));
