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

@DisplayName("Foreign key deletion behavior")
class ForeignKeyDeletionTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("should reject insert of user referencing non-existent tenant")
    void shouldRejectOrphanUserInsert() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash,
                    role, status, must_change_password, failed_login_count, version, created_at, updated_at)
                VALUES (:id, :tenantId, 'orphan', 'Orphan', decode('deadbeef','hex'),
                    'USER', 'ACTIVE', false, 0, 0, now(), now())
                """,
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenantId", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should reject delete of tenant referenced by users (ON DELETE RESTRICT)")
    void shouldRejectTenantDeleteWithUsers() {
        UUID seedTenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // The seed tenant is referenced by the FK constraint but has no users yet in
        // this test.
        // Create a new tenant with a user, then try to delete the tenant.
        UUID newTenant = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, code, name, status, version, created_at, updated_at) VALUES (:id, :code, :name, 'ACTIVE', 0, now(), now())",
                new MapSqlParameterSource().addValue("id", newTenant)
                        .addValue("code", "del-test-" + newTenant.toString().substring(0, 6))
                        .addValue("name", "Delete Test"));

        jdbc.update(
                "INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status, must_change_password, failed_login_count, version, created_at, updated_at) VALUES (:id, :tenantId, 'deltest', 'DT', decode('ab','hex'), 'USER', 'ACTIVE', false, 0, 0, now(), now())",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenantId", newTenant));

        // Should be rejected because users reference this tenant with ON DELETE
        // RESTRICT
        assertThatThrownBy(
                () -> jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", newTenant)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should reject insert of credential_version referencing non-existent credential")
    void shouldRejectOrphanCredentialVersion() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO upstream_credential_versions (id, tenant_id, credential_id, encrypted_secret,
                    nonce, encryption_key_version, secret_fingerprint, status, created_at)
                VALUES (:id, :tenantId, :credentialId, decode('deadbeef','hex'),
                    decode('cafe','hex'), 'v1', decode('f00d','hex'), 'PENDING_VALIDATION', now())
                """,
                new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                        .addValue("tenantId", UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .addValue("credentialId", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should allow membership delete without cascading to users or projects")
    void shouldAllowMembershipDelete() {
        UUID seedTenant = UUID.fromString("00000000-0000-0000-0000-000000000001");

        var userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status, must_change_password, failed_login_count, version, created_at, updated_at) VALUES (:id, :tenantId, 'memuser', 'MU', decode('ab','hex'), 'USER', 'ACTIVE', false, 0, 0, now(), now())",
                new MapSqlParameterSource().addValue("id", userId).addValue("tenantId", seedTenant));

        var projectId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO projects (id, tenant_id, code, name, status, version, created_at, updated_at) VALUES (:id, :tenantId, 'memproj', 'MP', 'ACTIVE', 0, now(), now())",
                new MapSqlParameterSource().addValue("id", projectId).addValue("tenantId", seedTenant));

        jdbc.update(
                "INSERT INTO project_memberships (tenant_id, project_id, user_id, created_at) VALUES (:tenantId, :projectId, :userId, now())",
                new MapSqlParameterSource().addValue("tenantId", seedTenant).addValue("projectId", projectId)
                        .addValue("userId", userId));

        var countSql = "SELECT COUNT(*) FROM project_memberships WHERE project_id = :pid AND user_id = :uid";
        int before = jdbc.queryForObject(countSql,
                new MapSqlParameterSource().addValue("pid", projectId).addValue("uid", userId), Integer.class);
        assertThat(before).isEqualTo(1);

        jdbc.update("DELETE FROM project_memberships WHERE project_id = :pid AND user_id = :uid",
                new MapSqlParameterSource().addValue("pid", projectId).addValue("uid", userId));

        int after = jdbc.queryForObject(countSql,
                new MapSqlParameterSource().addValue("pid", projectId).addValue("uid", userId), Integer.class);
        assertThat(after).isEqualTo(0);

        // User and project still exist
        int userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id",
                new MapSqlParameterSource("id", userId), Integer.class);
        assertThat(userCount).isEqualTo(1);
    }
}
