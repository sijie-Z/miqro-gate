-- ============================================================================
-- V65 (#758): composite index for the usage-stats lifecycle join.
--
-- The usage statistics read path enriches fact rows (`usage_event`) with the
-- per-request lifecycle trail (`request_usage_records`: wire protocol, first
-- byte time, terminal status) by joining on (tenant_id, gateway_request_id).
-- The existing unique key is (started_at, gateway_request_id) — it cannot
-- serve lookups that do not know the exact started_at, so the join would scan
-- every partition. This index makes the lookup an index probe per partition.
-- ============================================================================

CREATE INDEX idx_request_usage_records_tenant_gateway_request
    ON request_usage_records (tenant_id, gateway_request_id);
