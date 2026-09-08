-- V34: model_catalog.source — F18 manual model entry provenance.
-- Rows fetched from the provider official API are 'OFFICIAL'; models entered
-- by an administrator as a probe-failure fallback are 'MANUAL'. Official
-- fetches never overwrite or prune MANUAL rows (the unique key keeps the
-- manual row when the API later lists the same model id).

ALTER TABLE model_catalog ADD COLUMN source varchar(16) NOT NULL DEFAULT 'OFFICIAL'
    CHECK (source IN ('OFFICIAL', 'MANUAL'));
