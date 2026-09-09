-- V37: API consumer capability scope (issue #316).
-- NULL capabilities = full access (all existing consumers stay full); a JSON
-- array of capability codes narrows the consumer to the listed channels
-- (billing:read = control-plane /api/v1/billing/**, mcp:call = MCP data
-- plane). Validation of codes happens in the application layer (mirror of the
-- admin_api_keys.scope V35 pattern); enforcement is fail-closed per channel.
ALTER TABLE api_consumers ADD COLUMN capabilities jsonb;
