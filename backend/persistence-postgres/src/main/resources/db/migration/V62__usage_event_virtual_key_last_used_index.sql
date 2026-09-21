-- ============================================================================
-- 62. Usage-event per-key last-used index (issue #729).
--
-- The virtual-key list/detail/rotate paths compute "last used" with
--   LEFT JOIN (SELECT virtual_key_id, MAX(occurred_at)
--              FROM usage_event GROUP BY virtual_key_id) lu
-- over a table that is retained forever by design. The single-column
-- idx_usage_event_virtual_key_id (V6) cannot answer the per-key MAX, and the
-- plain idx_usage_event_occurred_at (V6) orders globally, not per key — so the
-- aggregate degrades toward a full scan of an ever-growing table.
--
-- The composite (virtual_key_id, occurred_at DESC) lets PostgreSQL answer
-- each key's maximum with an index-only backward scan, so admin list latency
-- stops growing linearly with retained history. No query or schema change:
-- the existing SQL's plan improves on its own.
-- ============================================================================

CREATE INDEX idx_usage_event_virtual_key_occurred
    ON usage_event (virtual_key_id, occurred_at DESC);
