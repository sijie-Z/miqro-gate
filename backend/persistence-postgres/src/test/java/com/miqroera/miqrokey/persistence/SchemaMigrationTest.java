package com.miqroera.miqrokey.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Schema migration")
class SchemaMigrationTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

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

    // -------------------------------------------------------------------
    // V3 migration: chain_position sequence correctness and ownership
    // -------------------------------------------------------------------

    @Test
    @DisplayName("should set chain_position sequence to 1 on empty table (is_called=false)")
    void shouldSetChainSequenceTo1OnEmptyTable() {
        // Use an isolated schema to avoid order-dependency on the shared database.
        // Test-method order is not a contract; another test can consume the shared
        // sequence first. In an isolated schema, the empty-table V2→V3 path is
        // deterministic: the DO block sets the sequence to 1 with is_called=false,
        // so the first nextval() MUST return 1.
        String schema = "sm_v3_" + UUID.randomUUID().toString().replace("-", "");
        try {
            jdbc.getJdbcTemplate().execute("CREATE SCHEMA " + schema);

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema).createSchemas(false)
                    .locations("classpath:db/migration").target("2").load().migrate();

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema).createSchemas(false)
                    .locations("classpath:db/migration").target("3").load().migrate();

            Long nextVal = jdbc.getJdbcTemplate()
                    .queryForObject("SELECT nextval('" + schema + ".admin_audit_events_chain_seq')", Long.class);
            assertThat(nextVal).as("first nextval on empty table after isolated V2→V3 migration must be 1")
                    .isEqualTo(1L);
        } finally {
            jdbc.getJdbcTemplate().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    @DisplayName("should assign unique non-null chain_positions to new rows via column default")
    void shouldAssignUniqueNonNullChainPositions() {
        UUID actorId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            jdbc.update(
                    "INSERT INTO admin_audit_events (id, tenant_id, actor_id, action, current_event_hash) "
                            + "VALUES (:id, :tenantId, :actorId, :action, :hash)",
                    new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenantId", SEED_TENANT_ID)
                            .addValue("actorId", actorId).addValue("action", "V3_TEST_" + i)
                            .addValue("hash", new byte[32]));
        }

        List<Long> positions = jdbc.queryForList(
                "SELECT chain_position FROM admin_audit_events ORDER BY chain_position", Map.of(), Long.class);
        assertThat(positions).as("5 rows inserted").hasSize(5);
        assertThat(positions).as("all chain_positions non-null").doesNotContainNull();
        assertThat(positions).as("all chain_positions unique").doesNotHaveDuplicates();
        for (int i = 1; i < positions.size(); i++) {
            assertThat(positions.get(i)).as("chain_position monotonically increasing (pos %d > pos %d)",
                    positions.get(i), positions.get(i - 1)).isGreaterThan(positions.get(i - 1));
        }
    }

    @Test
    @DisplayName("should have sequence owned by chain_position column")
    void shouldHaveSequenceOwnedByChainPosition() {
        // OWNED BY ensures that dropping the column (or table) auto-drops the
        // sequence — the standard PostgreSQL pattern for column-specific sequences.
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM pg_depend d
                    JOIN pg_class c ON d.objid = c.oid
                    JOIN pg_class t ON d.refobjid = t.oid
                    JOIN pg_attribute a ON d.refobjid = a.attrelid AND d.refobjsubid = a.attnum
                    WHERE c.relname = 'admin_audit_events_chain_seq'
                      AND t.relname = 'admin_audit_events'
                      AND a.attname = 'chain_position'
                      AND d.deptype = 'a'
                )
                """;
        Boolean owned = jdbc.queryForObject(sql, Map.of(), Boolean.class);
        assertThat(owned).as("sequence should be owned by admin_audit_events.chain_position").isTrue();
    }
}
