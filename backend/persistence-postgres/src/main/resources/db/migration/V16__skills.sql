-- V16: SkillHub catalog (P2.2, Anthropic Agent Skills format)
-- Skills are validated zip packages (SKILL.md + optional resources); the
-- catalog metadata is parsed from the SKILL.md frontmatter at upload time.
-- Visibility: every ACTIVE skill is visible to all signed-in users.
-- Download: gated by skill_access (TEAM/PROJECT scopes; no rows = public).

CREATE TABLE skills (
    id              uuid          PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name            varchar(64)   NOT NULL,          -- kebab-case, equals the SKILL.md frontmatter name
    description     varchar(1024) NOT NULL,
    version         varchar(32)   NOT NULL,
    author          varchar(200),
    license         varchar(64),
    tags            text[]        NOT NULL DEFAULT '{}',
    content_zip     bytea         NOT NULL,          -- validated skill package
    content_sha256  varchar(64)   NOT NULL,
    content_bytes   bigint        NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by      uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    row_version     bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_skills_tenant_name ON skills (tenant_id, name);

CREATE TABLE skill_access (
    id          uuid          PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    skill_id    uuid          NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    scope_type  varchar(16)   NOT NULL CHECK (scope_type IN ('TEAM', 'PROJECT')),
    scope_id    uuid          NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_access_scope UNIQUE (skill_id, scope_type, scope_id)
);

CREATE INDEX idx_skill_access_skill ON skill_access (skill_id);
