-- V38: MCP upstream backend authentication (issue #320, Tencent raw 03).
-- VISITOR (default, existing rows unchanged): the gateway forwards with no
-- upstream credential; API_KEY: the gateway attaches a fixed
-- `Authorization: Bearer <secret>` header, mirroring the model plane's
-- encrypted-credential injection. The secret is stored AES-GCM encrypted
-- (same key ring + AAD binding as upstream_credentials) and is write-only:
-- no read surface ever returns it.
ALTER TABLE mcp_services
    ADD COLUMN backend_auth_mode varchar(16) NOT NULL DEFAULT 'VISITOR'
        CHECK (backend_auth_mode IN ('VISITOR', 'API_KEY')),
    ADD COLUMN backend_secret_ciphertext bytea,
    ADD COLUMN backend_secret_nonce bytea,
    ADD COLUMN backend_secret_key_version varchar(32),
    ADD COLUMN backend_secret_updated_at timestamptz;

-- The mode and the ciphertext must always agree: API_KEY iff a full
-- ciphertext triple exists.
ALTER TABLE mcp_services
    ADD CONSTRAINT ck_mcp_services_backend_secret
        CHECK ((backend_auth_mode = 'API_KEY') = (backend_secret_ciphertext IS NOT NULL
               AND backend_secret_nonce IS NOT NULL AND backend_secret_key_version IS NOT NULL));
