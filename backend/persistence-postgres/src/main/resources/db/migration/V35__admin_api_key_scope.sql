-- V35: admin API key capability scope (F60 batch 3, ADR-0015 增补).
-- NULL scope = full access (all existing keys stay full); a JSON array of
-- capability codes narrows the machine key to the listed groups. The filter
-- enforces the mapping path -> capability on every open-admin request.
ALTER TABLE admin_api_keys ADD COLUMN scope jsonb;
