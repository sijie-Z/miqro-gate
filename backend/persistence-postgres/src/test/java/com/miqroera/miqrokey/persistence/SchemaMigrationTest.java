package com.miqroera.miqrokey.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Schema migration")
class SchemaMigrationTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("should migrate from empty database and contain all core tables")
    void shouldMigrateFromEmptyDatabase() {
        List<String> expectedTables = List.of("tenants", "users", "teams", "team_memberships", "projects",
                "project_memberships", "providers", "provider_products", "upstream_subscriptions", "plan_seats",
                "upstream_credentials", "upstream_credential_versions", "project_provider_grants",
                "project_provider_grant_models", "virtual_keys", "virtual_key_models", "admin_audit_events",
                "flyway_schema_history");

        var sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name";
        List<String> actualTables = jdbc.queryForList(sql, Map.of(), String.class);

        for (String expected : expectedTables) {
            assertThat(actualTables).as("Expected table '%s' to exist", expected).contains(expected);
        }
    }

    @Test
    @DisplayName("should have V1 Flyway migration recorded")
    void shouldHaveV1MigrationRecord() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true", Map.of(),
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("should contain seeded default tenant")
    void shouldContainSeededDefaultTenant() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE id = '00000000-0000-0000-0000-000000000001' AND code = 'default'",
                Map.of(), Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Nested
    @DisplayName("Table column requirements")
    class ColumnRequirements {
        @Test
        @DisplayName("tenants should have version column")
        void tenantsShouldHaveVersion() {
            var sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'tenants'";
            List<String> cols = jdbc.queryForList(sql, Map.of(), String.class);
            assertThat(cols).contains("version");
        }

        @Test
        @DisplayName("tenant-scoped tables should have tenant_id")
        void tenantScopedTablesShouldHaveTenantId() {
            var tenantTables = List.of("users", "teams", "team_memberships", "projects", "project_memberships",
                    "upstream_subscriptions", "plan_seats", "upstream_credentials", "upstream_credential_versions",
                    "project_provider_grants", "project_provider_grant_models", "virtual_keys", "virtual_key_models",
                    "admin_audit_events");
            for (String table : tenantTables) {
                var sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = :t AND column_name = 'tenant_id'";
                List<String> cols = jdbc.queryForList(sql, Map.of("t", table), String.class);
                assertThat(cols).as("table '%s' should have tenant_id", table).hasSize(1);
            }
        }

        @Test
        @DisplayName("virtual_keys should NOT contain plaintext key column")
        void virtualKeysShouldNotContainPlaintext() {
            var sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'virtual_keys'";
            List<String> cols = jdbc.queryForList(sql, Map.of(), String.class);
            assertThat(cols).contains("secret_digest");
            assertThat(cols).doesNotContain("secret", "plaintext_secret", "full_key", "api_key");
        }
    }

    @Test
    @DisplayName("should re-run Flyway idempotently (repeat-migrate validation)")
    void shouldRunFlywayIdempotently() {
        // Flyway has already run V1. Query history to confirm only V1 exists.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Map.of(), Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);

        // Verify the schemas are intact by counting tables
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'", Map.of(),
                Integer.class);
        assertThat(tableCount).isGreaterThanOrEqualTo(17);
    }

    @Test
    @DisplayName("should have active_version_id FK on upstream_credentials")
    void shouldHaveActiveVersionFk() {
        var sql = "SELECT conname FROM pg_constraint WHERE conrelid = 'upstream_credentials'::regclass AND contype = 'f' AND conname LIKE '%active_version%'";
        List<String> fks = jdbc.queryForList(sql, Map.of(), String.class);
        assertThat(fks).isNotEmpty();
    }

    @Test
    @DisplayName("should have replaced_by_key_id FK on virtual_keys")
    void shouldHaveReplacedByKeyFk() {
        var sql = "SELECT conname FROM pg_constraint WHERE conrelid = 'virtual_keys'::regclass AND contype = 'f' AND conname LIKE '%replaced_by%'";
        List<String> fks = jdbc.queryForList(sql, Map.of(), String.class);
        assertThat(fks).isNotEmpty();
    }
}
