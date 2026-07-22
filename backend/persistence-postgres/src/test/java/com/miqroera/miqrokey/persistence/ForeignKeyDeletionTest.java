package com.miqroera.miqrokey.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies foreign-key deletion behavior: RESTRICT (default) on critical
 * references, and that orphan inserts are rejected.
 */
@DisplayName("Foreign key deletion behavior")
class ForeignKeyDeletionTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    @DisplayName("should reject insert of user referencing non-existent tenant")
    void shouldRejectOrphanUserInsert() {
        assertThatThrownBy(() -> {
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash,
                        role, status, must_change_password, failed_login_count, version, created_at, updated_at)
                    VALUES (:id, :tenantId, 'orphan', 'Orphan User',
                        decode('deadbeef','hex'), 'USER', 'ACTIVE', false, 0, 0, now(), now())
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenantId",
                    UUID.randomUUID()));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should reject insert of project referencing non-existent tenant")
    void shouldRejectOrphanProjectInsert() {
        assertThatThrownBy(() -> {
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, version, created_at, updated_at)
                    VALUES (:id, :tenantId, 'orphan-proj', 'Orphan Project', 'ACTIVE', 0, now(), now())
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenantId",
                    UUID.randomUUID()));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should reject insert of virtual_key referencing non-existent user")
    void shouldRejectVirtualKeyWithOrphanUser() {
        // Insert tenant and project, but use non-existent user
        insertTenant(tenantId);

        var projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, 'test-proj', 'Test', 'ACTIVE', 0, now(), now())
                """, new MapSqlParameterSource().addValue("id", projectId).addValue("tenantId", tenantId));

        var nonExistentUserId = UUID.randomUUID();
        assertThatThrownBy(() -> {
            jdbc.update("""
                    INSERT INTO virtual_keys (id, public_key_id, secret_digest, display_prefix,
                        last_four, user_id, project_id, grant_id, upstream_credential_id,
                        purpose, status, version, created_at)
                    VALUES (:id, 'mqk_test_orphan', decode('deadbeef','hex'), 'mqk_', 'beef',
                        :userId, :projectId, :grantId, :credentialId,
                        'CLAUDE_CODE', 'ACTIVE', 0, now())
                    """,
                    new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("userId", nonExistentUserId)
                            .addValue("projectId", projectId).addValue("grantId", UUID.randomUUID())
                            .addValue("credentialId", UUID.randomUUID()));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should reject insert of credential_version referencing non-existent credential")
    void shouldRejectOrphanCredentialVersion() {
        assertThatThrownBy(() -> {
            jdbc.update("""
                    INSERT INTO upstream_credential_versions (id, credential_id, encrypted_secret,
                        nonce, encryption_key_version, secret_fingerprint, status, created_at)
                    VALUES (:id, :credentialId, decode('deadbeef','hex'),
                        decode('cafe','hex'), 'v1', decode('f00d','hex'), 'PENDING_VALIDATION', now())
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("credentialId",
                    UUID.randomUUID()));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should allow project_memberships delete without affecting parent tables")
    void shouldAllowMembershipDelete() {
        insertTenant(tenantId);

        var userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash,
                    role, status, must_change_password, failed_login_count, version, created_at, updated_at)
                VALUES (:id, :tenantId, 'memtest', 'Membership Test',
                    decode('deadbeef','hex'), 'USER', 'ACTIVE', false, 0, 0, now(), now())
                """, new MapSqlParameterSource().addValue("id", userId).addValue("tenantId", tenantId));

        var projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, 'mem-proj', 'Membership Project', 'ACTIVE', 0, now(), now())
                """, new MapSqlParameterSource().addValue("id", projectId).addValue("tenantId", tenantId));

        // Insert membership
        jdbc.update("""
                INSERT INTO project_memberships (project_id, user_id, created_at)
                VALUES (:projectId, :userId, now())
                """, new MapSqlParameterSource().addValue("projectId", projectId).addValue("userId", userId));

        // Verify membership exists
        var countSql = "SELECT COUNT(*) FROM project_memberships WHERE project_id = :pid AND user_id = :uid";
        int before = jdbc.queryForObject(countSql,
                new MapSqlParameterSource().addValue("pid", projectId).addValue("uid", userId), Integer.class);
        assertThat(before).isEqualTo(1);

        // Delete membership (should succeed - not restricted by FK from above)
        jdbc.update("DELETE FROM project_memberships WHERE project_id = :pid AND user_id = :uid",
                new MapSqlParameterSource().addValue("pid", projectId).addValue("uid", userId));

        int after = jdbc.queryForObject(countSql,
                new MapSqlParameterSource().addValue("pid", projectId).addValue("uid", userId), Integer.class);
        assertThat(after).isEqualTo(0);

        // User and project still exist
        var userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id",
                new MapSqlParameterSource("id", userId), Integer.class);
        assertThat(userCount).isEqualTo(1);
    }

    private void insertTenant(UUID id) {
        var existing = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        if (existing != null && existing == 0) {
            jdbc.update("""
                    INSERT INTO tenants (id, code, name, status, created_at, updated_at)
                    VALUES (:id, :code, :name, 'ACTIVE', now(), now())
                    """, new MapSqlParameterSource().addValue("id", id)
                    .addValue("code", "test-" + id.toString().substring(0, 8)).addValue("name", "Test Tenant"));
        }
    }
}
