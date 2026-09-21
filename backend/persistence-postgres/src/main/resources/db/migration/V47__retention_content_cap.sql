-- V47: retention_config.max_content_bytes (#367, I18, raw doc 26 derived).
-- The retention channel now caps ONE captured user-text payload per tenant;
-- content beyond the cap is truncated on a UTF-8 boundary (previously the
-- gateway dropped the whole event) and flagged/counted for observability.
ALTER TABLE retention_config
    ADD COLUMN max_content_bytes integer NOT NULL DEFAULT 262144
        CHECK (max_content_bytes BETWEEN 1024 AND 4194304);
