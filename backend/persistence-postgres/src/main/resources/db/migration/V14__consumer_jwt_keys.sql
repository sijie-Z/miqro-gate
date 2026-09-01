-- V14: consumer JWT verification keys (ADR-0011)
-- External systems may authenticate with a self-signed RS256 JWT instead of
-- the API key; the gateway only stores the verification public key (PEM),
-- its SHA-256 fingerprint (display only) and the time it was set.

ALTER TABLE api_consumers
    ADD COLUMN jwt_public_key_pem text,
    ADD COLUMN jwt_key_fingerprint varchar(16),
    ADD COLUMN jwt_key_set_at timestamptz;
