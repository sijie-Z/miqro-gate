-- V10: cost_allocations (G4.3)
--
-- Per-subscription-period cost allocation rows: for every project (and
-- optionally user) that consumed subscription quota, records the fixed-cost
-- share (Plan subscription price prorated to the period, weighted by token
-- share), the metered usage cost (PAYG from local usage x price snapshots),
-- the weighting tokens, and the total allocated amount. The unique key
-- (subscription_id, period_start, period_end, target_type, target_id,
-- algorithm_version) makes re-allocation idempotent and versioned — a new
-- algorithm bumps the version instead of overwriting history.

CREATE TABLE cost_allocations (
    id                 uuid         PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    subscription_id    uuid         NOT NULL REFERENCES upstream_subscriptions (id) ON DELETE CASCADE,
    period_start       timestamptz  NOT NULL,
    period_end         timestamptz  NOT NULL,
    target_type        varchar(16)  NOT NULL
                       CHECK (target_type IN ('PROJECT', 'USER')),
    target_id          uuid         NOT NULL,
    fixed_cost         numeric(24,10) NOT NULL DEFAULT 0,
    usage_cost         numeric(24,10) NOT NULL DEFAULT 0,
    weight_tokens      bigint       NOT NULL DEFAULT 0,
    allocated_amount   numeric(24,10) NOT NULL DEFAULT 0,
    currency           varchar(3)   NOT NULL,
    algorithm_version  varchar(16)  NOT NULL,
    generated_at       timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (subscription_id, period_start, period_end, target_type, target_id, algorithm_version)
);

CREATE INDEX idx_cost_allocations_lookup
    ON cost_allocations (tenant_id, subscription_id, period_start, period_end);
