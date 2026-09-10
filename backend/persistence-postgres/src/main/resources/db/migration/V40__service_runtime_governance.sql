-- V40: internal service runtime governance (issue #326, mirror of the
-- mcp_services V20 health model). Adds the health probe configuration and
-- state columns; existing rows keep working (UNKNOWN until first probe).
-- ACTIVE services are probed on their own interval; DISABLED rows are never
-- probed (a manual stop is never overridden), mirroring the MCP semantics.
ALTER TABLE services
    ADD COLUMN health_status varchar(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (health_status IN ('UNKNOWN', 'HEALTHY', 'UNHEALTHY')),
    ADD COLUMN health_checked_at timestamptz,
    ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN consecutive_successes integer NOT NULL DEFAULT 0,
    ADD COLUMN check_interval_seconds integer NOT NULL DEFAULT 30,
    ADD COLUMN check_timeout_seconds integer NOT NULL DEFAULT 5,
    ADD COLUMN fail_threshold integer NOT NULL DEFAULT 3,
    ADD COLUMN recover_threshold integer NOT NULL DEFAULT 1,
    ADD COLUMN check_path varchar(512) NOT NULL DEFAULT '/health';
