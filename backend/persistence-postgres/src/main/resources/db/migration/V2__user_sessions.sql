-- ============================================================================
-- V2: User sessions table for server-side revocable authentication sessions.
--
-- Stores only SHA-256 digests of session tokens and CSRF secrets. Raw tokens
-- never touch the database. Sessions support expiry, revocation, and per-user
-- bulk revocation (e.g., password change revokes all other sessions).
-- ============================================================================

CREATE TABLE user_sessions (
    id                uuid         PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    user_id           uuid         NOT NULL,
    token_digest      bytea        NOT NULL,
    csrf_digest       bytea        NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    last_seen_at      timestamptz  NOT NULL DEFAULT now(),
    expires_at        timestamptz  NOT NULL,
    revoked_at        timestamptz,
    CONSTRAINT fk_user_sessions_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id) ON DELETE RESTRICT
);

-- Fast lookup by token digest is the hot path (every authenticated request)
CREATE UNIQUE INDEX uq_user_sessions_token_digest ON user_sessions (token_digest);

-- Support session listing/revocation per user
CREATE INDEX idx_user_sessions_user_id ON user_sessions (user_id);

-- Support expiry cleanup
CREATE INDEX idx_user_sessions_expires_at ON user_sessions (expires_at)
    WHERE revoked_at IS NULL;

-- Support finding active sessions for a user (revoke-others)
CREATE INDEX idx_user_sessions_user_active ON user_sessions (user_id, id)
    WHERE revoked_at IS NULL;
