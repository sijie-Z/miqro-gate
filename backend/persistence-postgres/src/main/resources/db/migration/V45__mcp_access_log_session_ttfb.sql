-- V45: mcp_access_log gains session correlation + upstream first-byte latency
-- (issue #358, I12, raw doc 16). session_id = the client-presented Session-Id
-- header (or the inbound SSE session id for SSE-transport calls); ttfb_ms =
-- upstream time-to-first-byte in milliseconds, recorded on FORWARDED rows only.
ALTER TABLE mcp_access_log
    ADD COLUMN session_id varchar(128),
    ADD COLUMN ttfb_ms    bigint;
