-- V33: mcp_tool_revisions — F16 tool definition versioning (Tencent raw 11).
-- Definition edits become immutable snapshots: publishing an edit creates the
-- next revision and moves the activation pointer; the active revision's spec
-- is mirrored onto mcp_tools (runtime reads the parent row via the route
-- snapshot). Historic revisions are never pruned; rollback = activating an
-- older revision (idempotent, no new revision number). Status toggles keep
-- their own optimistic counter on mcp_tools and do not create revisions.

CREATE TABLE mcp_tool_revisions (
    id            uuid         PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    tool_id       uuid         NOT NULL REFERENCES mcp_tools (id) ON DELETE CASCADE,
    revision      bigint       NOT NULL CHECK (revision > 0),
    description   varchar(2000),
    method        varchar(16)  NOT NULL CHECK (method IN ('GET', 'POST', 'PUT', 'DELETE', 'PATCH')),
    path          varchar(512) NOT NULL,
    created_by    uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    activated_at  timestamptz,
    CONSTRAINT uq_mcp_tool_revision UNIQUE (tool_id, revision)
);

-- At most one active revision per tool at any time.
CREATE UNIQUE INDEX uq_mcp_tool_active_revision
    ON mcp_tool_revisions (tool_id) WHERE activated_at IS NOT NULL;

-- History listing shape: newest first.
CREATE INDEX idx_mcp_tool_revisions
    ON mcp_tool_revisions (tool_id, revision DESC);
