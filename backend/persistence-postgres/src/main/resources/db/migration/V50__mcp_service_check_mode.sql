-- V50: mcp_services.check_mode (#387, Tencent raw 135906 health semantics).
-- The health probe was fixed to GET endpoint + check_path; standard MCP
-- (streamable HTTP) servers usually expose no HTTP health path — the
-- protocol-native liveness probe is a JSON-RPC `initialize` POST. HEALTH_PATH
-- keeps the existing behavior (default); JSONRPC_INITIALIZE switches the probe
-- to POST endpoint with an initialize envelope (2xx + a JSON-RPC body counts
-- as healthy; API_KEY backends get the decrypted bearer attached).
ALTER TABLE mcp_services
    ADD COLUMN check_mode varchar(24) NOT NULL DEFAULT 'HEALTH_PATH'
        CHECK (check_mode IN ('HEALTH_PATH', 'JSONRPC_INITIALIZE'));
