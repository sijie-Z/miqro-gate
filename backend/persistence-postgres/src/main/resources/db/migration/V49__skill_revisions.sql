-- V49: skill_revisions — I14 skill version management (Tencent raw 20).
-- A same-name skill upload publishes the next immutable revision and moves the
-- activation pointer; the active revision's metadata + package are mirrored
-- onto skills so catalog reads and downloads stay unchanged. History is never
-- pruned; rollback = activating an older revision (idempotent, no new number).
-- Skills uploaded before this migration become revision 1, active.

CREATE TABLE skill_revisions (
    id             uuid          PRIMARY KEY,
    tenant_id      uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    skill_id       uuid          NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    revision       bigint        NOT NULL CHECK (revision > 0),
    version        varchar(32)   NOT NULL,
    description    varchar(1024) NOT NULL,
    author         varchar(200),
    license        varchar(64),
    tags           text[]        NOT NULL DEFAULT '{}',
    examples       text[]        NOT NULL DEFAULT '{}',
    content_zip    bytea         NOT NULL,
    content_sha256 varchar(64)   NOT NULL,
    content_bytes  bigint        NOT NULL,
    created_by     uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    activated_at   timestamptz,
    CONSTRAINT uq_skill_revision UNIQUE (skill_id, revision)
);

-- At most one active revision per skill at any time.
CREATE UNIQUE INDEX uq_skill_active_revision
    ON skill_revisions (skill_id) WHERE activated_at IS NOT NULL;

-- History listing shape: newest first.
CREATE INDEX idx_skill_revisions
    ON skill_revisions (skill_id, revision DESC);

-- Backfill: every existing skill row becomes its revision 1 (active).
INSERT INTO skill_revisions (id, tenant_id, skill_id, revision, version, description, author, license, tags,
    examples, content_zip, content_sha256, content_bytes, created_by, created_at, activated_at)
SELECT gen_random_uuid(), tenant_id, id, 1, version, description, author, license, tags, examples, content_zip,
    content_sha256, content_bytes, created_by, created_at, now()
FROM skills;
