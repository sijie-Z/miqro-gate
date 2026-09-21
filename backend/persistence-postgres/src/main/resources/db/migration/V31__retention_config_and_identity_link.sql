-- ============================================================================
-- V31: retention_config + user_identity_link — ADR-0014 v3 Accepted (2026-09-05)
--
-- 1) retention_config — per-tenant compliance-retention switch (default OFF;
--    CLAUDE.md body-storage red line waived ONLY behind this row, see
--    ADR-0014 §1). content_scope = USER_TEXT_ONLY (P1 default: only user
--    message text is ever collected; model replies / tool payloads are out of
--    scope in v1). key_version references the deployment crypto key set used
--    for the envelope encryption (P5 env/KMS semantics). All changes are
--    audited (RETENTION_CONFIG_UPDATE) and reach the gateway through the route
--    snapshot, so a running gateway switches without restart.
--
-- 2) user_identity_link — OAuth platform mapping skeleton (R4/P7): platform
--    user_id + idp/issuer ↔ internal user, one row per (platform) identity.
--    Populated once the platform OAuth claims land; not read by the data plane
--    yet. Virtual-key events later carry the mapped platform uid.
-- ============================================================================

CREATE TABLE retention_config (
    tenant_id       uuid          NOT NULL PRIMARY KEY REFERENCES tenants (id) ON DELETE RESTRICT,
    enabled         boolean       NOT NULL DEFAULT FALSE,
    content_scope   varchar(32)   NOT NULL DEFAULT 'USER_TEXT_ONLY'
                    CHECK (content_scope IN ('USER_TEXT_ONLY')),
    key_version     varchar(64)   NOT NULL DEFAULT 'v1',
    version         integer       NOT NULL DEFAULT 0,
    updated_by      uuid,
    updated_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE user_identity_link (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    internal_user_id uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    platform_user_id varchar(256)  NOT NULL,
    idp              varchar(128)  NOT NULL,
    version          integer       NOT NULL DEFAULT 0,
    created_by       uuid,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now()
);

-- One mapping per (platform identity); an internal user may carry several
-- platform identities (row per idp) but not two of the same kind.
CREATE UNIQUE INDEX uq_user_identity_link_platform
    ON user_identity_link (tenant_id, idp, platform_user_id);
CREATE INDEX ix_user_identity_link_internal
    ON user_identity_link (tenant_id, internal_user_id);
