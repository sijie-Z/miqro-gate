-- V41: export reconcile level (issue #330, usage-accounting §11 / backlog F23
-- first slice). Task-level declaration of whether the export can be
-- reconciled by provider request ID: every row carries one (PROVIDER_ID_BACKED),
-- none does (LOCAL_ONLY), or a mix (PARTIAL). NULL for historical tasks and
-- empty windows (nothing to declare). The 净额/含调整 levels arrive with the
-- F20 adjustment mechanism; this column admits the base vocabulary only.
ALTER TABLE export_tasks
    ADD COLUMN reconcile_level varchar(32)
        CHECK (reconcile_level IN ('PROVIDER_ID_BACKED', 'PARTIAL', 'LOCAL_ONLY'));
