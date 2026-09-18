-- ============================================================================
-- V67: export task adjustment level (issue #716, backlog F23R second slice).
--
-- V41 declared whether an export can be reconciled against the provider's bill
-- by request ID, and left a note that "the 净额/含调整 levels arrive with the
-- F20 adjustment mechanism". They have now arrived (#709/#755) — but as their
-- own axis rather than extra members of reconcile_level, because the two
-- questions are independent: "can I tie these rows to the bill?" and "do these
-- numbers include corrections?". One enum would need a value per combination
-- (PROVIDER_ID_BACKED_AND_ADJUSTED, …) and would read as neither.
--
--   PRESENT — at least one row in the file carries a correction. The per-row
--             `adjusted` column and the `net*` columns are what a consumer
--             reconciles with; the observed columns are still the raw gateway
--             counts.
--   NONE    — no row was ever corrected, so the net columns simply repeat the
--             observed ones and can be ignored.
--   NULL    — historical tasks and empty windows: nothing to declare.
--
-- Per task rather than per row because it is what a consumer wants to know
-- before reading the file (or from the task list alone): whether the numbers in
-- hand need the net columns at all.
-- ============================================================================

ALTER TABLE export_tasks
    ADD COLUMN adjustment_level varchar(32)
        CHECK (adjustment_level IN ('NONE', 'PRESENT'));
