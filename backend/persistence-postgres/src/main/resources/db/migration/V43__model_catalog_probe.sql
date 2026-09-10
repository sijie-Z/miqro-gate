-- ============================================================================
-- 43. Model catalog probe visibility (#346, I4, Tencent raw doc 05).
--     The admin probe endpoint (official /models fetch) records its last
--     outcome on the product so a failed probe is visible next to the
--     unchanged last-successful catalog. model_catalog rows themselves stay
--     success-only: a failed probe never touches them.
-- ============================================================================
ALTER TABLE provider_products
    ADD COLUMN model_catalog_probe_status varchar(16)
        CHECK (model_catalog_probe_status IN ('SUCCEEDED', 'FAILED')),
    ADD COLUMN model_catalog_probe_error  varchar(500),
    ADD COLUMN model_catalog_model_count  integer,
    ADD COLUMN model_catalog_probed_at    timestamptz;
