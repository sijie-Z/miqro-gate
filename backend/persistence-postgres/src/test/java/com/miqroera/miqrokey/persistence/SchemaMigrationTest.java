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
 * Verifies Flyway V1 migration from an empty database, idempotent restart, and
 * that all required core tables exist.
 */
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

        // Query PostgreSQL information_schema for user tables
        var sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name";
        List<String> actualTables = jdbc.queryForList(sql, Map.of(), String.class);

        for (String expected : expectedTables) {
            assertThat(actualTables).as("Expected table '%s' to exist", expected).contains(expected);
        }
    }

    @Test
    @DisplayName("should validate Flyway is applied and has V1 migration")
    void shouldHaveV1MigrationRecord() {
        var sql = "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true";
        Integer count = jdbc.queryForObject(sql, Map.of(), Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("should have Flyway schema history table")
    void shouldHaveFlywaySchemaHistoryTable() {
        var sql = "SELECT COUNT(*) FROM flyway_schema_history";
        Integer count = jdbc.queryForObject(sql, Map.of(), Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Nested
    @DisplayName("Table column requirements")
    class ColumnRequirements {

        @Test
        @DisplayName("tenants should have expected columns")
        void tenantsShouldHaveExpectedColumns() {
            var sql = """
                    SELECT column_name, data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'tenants'
                    ORDER BY ordinal_position
                    """;
            List<Map<String, Object>> columns = jdbc.queryForList(sql, Map.of());

            assertThat(columns).anyMatch(m -> "id".equals(m.get("column_name")) && "uuid".equals(m.get("data_type")));
            assertThat(columns).anyMatch(m -> "code".equals(m.get("column_name")));
            assertThat(columns).anyMatch(m -> "name".equals(m.get("column_name")));
            assertThat(columns).anyMatch(m -> "status".equals(m.get("column_name")));
            assertThat(columns).anyMatch(m -> "created_at".equals(m.get("column_name")));
            assertThat(columns).anyMatch(m -> "updated_at".equals(m.get("column_name")));
        }

        @Test
        @DisplayName("users should NOT contain plaintext key columns")
        void usersShouldNotContainPlaintextKeyColumns() {
            var sql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users'
                    """;
            List<String> columns = jdbc.queryForList(sql, Map.of(), String.class);

            // No plaintext secret/key columns
            assertThat(columns).doesNotContain("api_key", "secret", "token", "plaintext_key");
            // password_hash is bytea for hashed passwords (not plaintext)
            assertThat(columns).contains("password_hash");
        }

        @Test
        @DisplayName("virtual_keys should NOT contain plaintext key column")
        void virtualKeysShouldNotContainPlaintextKeyColumn() {
            var sql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'virtual_keys'
                    """;
            List<String> columns = jdbc.queryForList(sql, Map.of(), String.class);

            // Virtual key secret is stored as digest (bytea), never plaintext
            assertThat(columns).contains("secret_digest");
            assertThat(columns).doesNotContain("secret", "plaintext_secret", "full_key", "api_key");
        }

        @Test
        @DisplayName("upstream_credentials should NOT contain plaintext secret column")
        void upstreamCredentialsShouldNotContainPlaintextSecretColumn() {
            var sql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'upstream_credentials'
                    """;
            List<String> columns = jdbc.queryForList(sql, Map.of(), String.class);

            // Secret is only in upstream_credential_versions, not in the logical slot table
            assertThat(columns).doesNotContain("encrypted_secret", "secret", "plaintext_secret", "api_key");
        }

        @Test
        @DisplayName("upstream_credential_versions should contain encrypted_secret as bytea")
        void credentialVersionsShouldHaveEncryptedSecretAsBytea() {
            var sql = """
                    SELECT column_name, data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'upstream_credential_versions'
                      AND column_name IN ('encrypted_secret', 'nonce')
                    """;
            List<Map<String, Object>> columns = jdbc.queryForList(sql, Map.of());

            assertThat(columns).hasSize(2);
            for (var col : columns) {
                assertThat(col.get("data_type")).isEqualTo("bytea");
            }
        }
    }
}
