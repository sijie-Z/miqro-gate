package com.miqroera.miqrokey.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that documented constraints (CHECK, UNIQUE, partial unique index)
 * and regular indexes are correctly created by V1 migration.
 */
@DisplayName("Constraints and indexes")
class ConstraintAndIndexTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Nested
    @DisplayName("Unique constraints")
    class UniqueConstraints {

        @Test
        @DisplayName("tenants should have unique constraint on code")
        void tenantsShouldHaveUniqueCode() {
            var constraints = getUniqueConstraints("tenants");
            assertThat(constraints).anyMatch(c -> c.contains("code"));
        }

        @Test
        @DisplayName("users should have unique index on (tenant_id, lower(username))")
        void usersShouldHaveUniqueTenantUsername() {
            var indexes = getIndexes("users");
            assertThat(indexes).anyMatch(i -> i.contains("uq_users_tenant_username"));
        }

        @Test
        @DisplayName("projects should have unique constraint on (tenant_id, code)")
        void projectsShouldHaveUniqueTenantCode() {
            var indexes = getIndexes("projects");
            assertThat(indexes).anyMatch(i -> i.contains("uq_projects_tenant_code"));
        }

        @Test
        @DisplayName("providers should have unique constraint on slug")
        void providersShouldHaveUniqueSlug() {
            var indexes = getIndexes("providers");
            assertThat(indexes).anyMatch(i -> i.contains("uq_providers_slug"));
        }

        @Test
        @DisplayName("provider_products should have unique on (provider_id, product_code)")
        void providerProductsShouldHaveUniqueProviderCode() {
            var indexes = getIndexes("provider_products");
            assertThat(indexes).anyMatch(i -> i.contains("uq_provider_products_provider_code"));
        }

        @Test
        @DisplayName("grants should have unique on (project_id, provider_product_id, upstream_credential_id)")
        void grantsShouldHaveUniqueProjectProductCredential() {
            var indexes = getIndexes("project_provider_grants");
            assertThat(indexes).anyMatch(i -> i.contains("uq_grants_project_product_credential"));
        }

        @Test
        @DisplayName("virtual_keys should have unique constraint on public_key_id")
        void virtualKeysShouldHaveUniquePublicKeyId() {
            var indexes = getIndexes("virtual_keys");
            assertThat(indexes).anyMatch(i -> i.contains("uq_virtual_keys_public_key_id"));
        }
    }

    @Nested
    @DisplayName("Partial unique indexes")
    class PartialUniqueIndexes {

        @Test
        @DisplayName("credential_versions should have partial unique for one ACTIVE per credential")
        void credentialVersionsShouldHavePartialUniqueActive() {
            var indexes = getIndexes("upstream_credential_versions");
            assertThat(indexes).anyMatch(i -> i.contains("uq_credential_versions_one_active"));
        }

        @Test
        @DisplayName("plan_seats should have partial unique on (subscription_id, external_seat_ref) WHERE NOT NULL")
        void planSeatsShouldHavePartialUniqueExternalRef() {
            var indexes = getIndexes("plan_seats");
            assertThat(indexes).anyMatch(i -> i.contains("uq_plan_seats_subscription_ext_ref"));
        }
    }

    @Nested
    @DisplayName("Foreign key constraints")
    class ForeignKeyConstraints {

        @Test
        @DisplayName("users should reference tenants")
        void usersShouldReferenceTenants() {
            var fks = getForeignKeys("users");
            assertThat(fks).anyMatch(fk -> fk.contains("tenants"));
        }

        @Test
        @DisplayName("projects should reference tenants")
        void projectsShouldReferenceTenants() {
            var fks = getForeignKeys("projects");
            assertThat(fks).anyMatch(fk -> fk.contains("tenants"));
        }

        @Test
        @DisplayName("virtual_keys should reference users, projects, grants, and credentials")
        void virtualKeysShouldReferenceCoreEntities() {
            var fks = getForeignKeys("virtual_keys");
            assertThat(fks).anyMatch(fk -> fk.contains("users"));
            assertThat(fks).anyMatch(fk -> fk.contains("projects"));
            assertThat(fks).anyMatch(fk -> fk.contains("project_provider_grants"));
            assertThat(fks).anyMatch(fk -> fk.contains("upstream_credentials"));
        }

        @Test
        @DisplayName("credential_versions should reference upstream_credentials")
        void credentialVersionsShouldReferenceCredentials() {
            var fks = getForeignKeys("upstream_credential_versions");
            assertThat(fks).anyMatch(fk -> fk.contains("upstream_credentials"));
        }

        @Test
        @DisplayName("grants should reference projects, products, and credentials")
        void grantsShouldReferenceCoreEntities() {
            var fks = getForeignKeys("project_provider_grants");
            assertThat(fks).anyMatch(fk -> fk.contains("projects"));
            assertThat(fks).anyMatch(fk -> fk.contains("provider_products"));
            assertThat(fks).anyMatch(fk -> fk.contains("upstream_credentials"));
        }
    }

    @Nested
    @DisplayName("Check constraints")
    class CheckConstraints {

        @Test
        @DisplayName("users role should have check constraint")
        void usersRoleShouldHaveCheckConstraint() {
            var checks = getCheckConstraints("users");
            assertThat(checks).anyMatch(c -> c.contains("role"));
        }

        @Test
        @DisplayName("virtual_keys purpose should have check constraint")
        void virtualKeysPurposeShouldHaveCheckConstraint() {
            var checks = getCheckConstraints("virtual_keys");
            assertThat(checks).anyMatch(c -> c.contains("purpose"));
        }

        @Test
        @DisplayName("virtual_keys status should have check constraint")
        void virtualKeysStatusShouldHaveCheckConstraint() {
            var checks = getCheckConstraints("virtual_keys");
            assertThat(checks).anyMatch(c -> c.contains("status"));
        }
    }

    // -- helpers --

    private List<String> getUniqueConstraints(String table) {
        var sql = """
                SELECT conname FROM pg_constraint
                WHERE conrelid = :table::regclass AND contype = 'u'
                UNION ALL
                SELECT indexname FROM pg_indexes
                WHERE tablename = :tableName AND indexname LIKE 'uq_%'
                """;
        return jdbc.queryForList(sql, Map.of("table", table, "tableName", table), String.class);
    }

    private List<String> getIndexes(String table) {
        var sql = "SELECT indexname FROM pg_indexes WHERE tablename = :table ORDER BY indexname";
        return jdbc.queryForList(sql, Map.of("table", table), String.class);
    }

    private List<String> getForeignKeys(String table) {
        var sql = """
                SELECT conname FROM pg_constraint
                WHERE conrelid = :table::regclass AND contype = 'f'
                """;
        return jdbc.queryForList(sql, Map.of("table", table), String.class);
    }

    private List<String> getCheckConstraints(String table) {
        var sql = """
                SELECT conname FROM pg_constraint
                WHERE conrelid = :table::regclass AND contype = 'c'
                """;
        return jdbc.queryForList(sql, Map.of("table", table), String.class);
    }
}
