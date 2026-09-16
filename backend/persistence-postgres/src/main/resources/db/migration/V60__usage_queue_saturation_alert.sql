-- ============================================================================
-- 60. Usage-queue saturation alert (F07, issue #245).
--
-- The three metric classes named by F07 were re-checked against the code:
-- parse failure is already covered by USAGE_MISSING_RATE and provider errors
-- by UPSTREAM_ERROR_RATE. Queue saturation is the only one with NO queryable
-- fact: it lives in the gateway process as an in-memory counter
-- (UsageEventBus.QueueMetrics.totalDropped) whose Micrometer gauges carry no
-- tenant label and are off by default.
--
-- This migration adds that missing data source. The gateway writes a FACT row
-- whenever it actually loses events; the control plane evaluates it with the
-- existing AlertEvaluator -> AlertEventDispatcher path, exactly like the other
-- metric alerts. AlertEvaluator can only see rows that already exist, so the
-- gateway cannot write alert_events directly: AlertEventDispatcher.retryDue()
-- scans only rows that already have a failed delivery attempt, and a
-- directly-inserted event would never be delivered.
--
-- tenant_id carries the GLOBAL gateway signal under the default (seed) tenant
-- (00000000-0000-0000-0000-000000000001, V1) because the queue is a process
-- resource, not a per-request one. Evaluation filters alert_rules by
-- tenant_id, so the platform-level signal fires platform-level rules and a
-- rule owned by any other tenant is never triggered by it. In a single-tenant
-- deployment this is strictly equivalent to a global signal.
--
-- Semantics: `dropped` is the number of usage/lifecycle facts the gateway gave
-- up on since the previous row — a count, not a ratio. It measures LOSS. A
-- saturated queue in WRITE_THROUGH mode manifests as a bounded publisher stall
-- (the event is still persisted), so it produces no drop and no row; that mode
-- trades audit loss for latency and is watched through its own warning log.
-- ============================================================================

ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_type_check;
ALTER TABLE alert_rules
    ADD CONSTRAINT alert_rules_type_check
    CHECK (type IN ('USAGE_MISSING_RATE', 'UPSTREAM_ERROR_RATE', 'BALANCE_UNAVAILABLE', 'USAGE_SURGE',
                    'BUDGET_THRESHOLD', 'QUOTA_THRESHOLD',
                    'MODEL_APPROVAL_SUBMITTED', 'MODEL_APPROVAL_APPROVED', 'MODEL_APPROVAL_REJECTED',
                    'ADMIN_API_KEY_EXPIRING', 'CONSUMER_KEY_EXPIRING',
                    'USAGE_QUEUE_SATURATION'));

-- One row per window in which the gateway dropped at least one fact; a healthy
-- gateway writes nothing at all (`dropped > 0` gate in the writer). The
-- process-level fields are diagnostic context for the operator reading the
-- alert, not part of the threshold.
CREATE TABLE gateway_queue_signal (
    id                 uuid         PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    occurred_at        timestamptz  NOT NULL,
    dropped            bigint       NOT NULL CHECK (dropped > 0),
    queued_high_water  integer,
    capacity           integer,
    saturation_mode    varchar(16),
    created_at         timestamptz  NOT NULL DEFAULT now()
);

-- The evaluator scans "SUM(dropped) per tenant over the last rolling hour":
-- newest-first range scan per tenant.
CREATE INDEX ix_gateway_queue_signal_tenant_occurred
    ON gateway_queue_signal (tenant_id, occurred_at DESC);
