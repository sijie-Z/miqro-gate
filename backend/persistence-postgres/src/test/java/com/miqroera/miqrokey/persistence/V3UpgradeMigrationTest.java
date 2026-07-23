package com.miqroera.miqrokey.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Comprehensive V2→V3 upgrade tests using <strong>isolated schemas</strong>.
 * <p>
 * Each test creates a unique PostgreSQL schema, runs Flyway through V2 in that
 * schema, optionally inserts representative pre-V3 rows, then runs the V3
 * migration, and verifies the results. Schemas are dropped in
 * {@code @AfterEach}. No test depends on shared database state or execution
 * order.
 * </p>
 * <p>
 * V1 and V2 migration files are <strong>never edited</strong> — these tests
 * exercise Flyway's programmatic API with version targets to simulate the real
 * upgrade path.
 * </p>
 */
@Tag("integration")
@DisplayName("V3 upgrade migration (isolated-schema V2→V3)")
class V3UpgradeMigrationTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final List<String> createdSchemas = new ArrayList<>();

    @AfterEach
    void dropIsolatedSchemas() {
        for (String schema : createdSchemas) {
            try {
                jdbc.getJdbcTemplate().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } catch (Exception e) {
                // best-effort cleanup — don't fail the test
            }
        }
        createdSchemas.clear();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Creates a uniquely-named schema, records it for cleanup, and returns the
     * schema name. The name is a simple lowercase-hex identifier — no quoting
     * needed in SQL.
     */
    private String createSchema(String prefix) {
        String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.getJdbcTemplate().execute("CREATE SCHEMA " + schema);
        createdSchemas.add(schema);
        return schema;
    }

    /**
     * Runs Flyway programmatically in {@code schema} up to {@code targetVersion}
     * (inclusive). Uses the shared Testcontainers DataSource but isolates every
     * table and history row in the named schema via {@code search_path}.
     */
    private void migrateTo(String schema, String targetVersion) {
        Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema).createSchemas(false)
                .locations("classpath:db/migration").target(targetVersion).load().migrate();
    }

    // -------------------------------------------------------------------
    // 1. Upgrade with existing rows — full backfill coverage
    // -------------------------------------------------------------------

    @Test
    @DisplayName("V2→V3 upgrade: backfills existing rows with unique non-null chain_positions")
    void shouldBackfillExistingRowsOnUpgrade() {
        String schema = createSchema("v3up_backfill");

        migrateTo(schema, "2");

        // Insert 7 pre-V3 rows with deterministic ordering via created_at
        UUID actorId = UUID.randomUUID();
        // Use monotonically increasing timestamps to ensure deterministic
        // backfill ordering (the V3 DO block orders by created_at, id)
        for (int i = 1; i <= 7; i++) {
            jdbc.getJdbcTemplate()
                    .update("INSERT INTO " + schema + ".admin_audit_events "
                            + "(id, tenant_id, actor_id, action, target_type, change_summary, "
                            + "current_event_hash, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, now() + (? || ' seconds')::interval)",
                            UUID.randomUUID(), SEED_TENANT_ID, actorId, "PRE_V3_" + i, "TYPE", String.valueOf(i),
                            new byte[32], i);
        }

        migrateTo(schema, "3");

        // All 7 rows must have non-null chain_position
        List<Long> positions = jdbc.getJdbcTemplate().queryForList(
                "SELECT chain_position FROM " + schema + ".admin_audit_events ORDER BY chain_position", Long.class);
        assertThat(positions).as("all 7 pre-V3 rows backfilled").hasSize(7);
        assertThat(positions).as("no null chain_positions").doesNotContainNull();
        assertThat(positions).as("all chain_positions unique").doesNotHaveDuplicates();

        // Backfilled positions must be monotonically increasing (assigned in
        // (created_at, id) order per the V3 DO block)
        for (int i = 1; i < positions.size(); i++) {
            assertThat(positions.get(i)).as("chain_positions are increasing").isGreaterThan(positions.get(i - 1));
        }
    }

    // -------------------------------------------------------------------
    // 2. Post-V3 insert after backfill
    // -------------------------------------------------------------------

    @Test
    @DisplayName("post-V3 insert receives chain_position greater than every backfilled row")
    void postV3InsertGetsPositionGreaterThanBackfilledMax() {
        String schema = createSchema("v3up_post");

        migrateTo(schema, "2");

        // Insert 3 pre-V3 rows
        UUID actorId = UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            jdbc.getJdbcTemplate().update(
                    "INSERT INTO " + schema + ".admin_audit_events "
                            + "(id, tenant_id, actor_id, action, target_type, change_summary, "
                            + "current_event_hash, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, now() + (? || ' seconds')::interval)",
                    UUID.randomUUID(), SEED_TENANT_ID, actorId, "PRE_" + i, "TYPE", String.valueOf(i), new byte[32], i);
        }

        migrateTo(schema, "3");

        // Insert one post-V3 row using the column DEFAULT
        UUID newId = UUID.randomUUID();
        jdbc.getJdbcTemplate()
                .update("INSERT INTO " + schema + ".admin_audit_events "
                        + "(id, tenant_id, actor_id, action, target_type, change_summary, current_event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", newId, SEED_TENANT_ID, actorId, "POST_V3", "TYPE",
                        "999", new byte[32]);

        Long postPosition = jdbc.getJdbcTemplate().queryForObject(
                "SELECT chain_position FROM " + schema + ".admin_audit_events WHERE id = ?", Long.class, newId);
        assertThat(postPosition).as("post-V3 row has a chain_position").isNotNull();

        // The post-V3 position must be greater than every backfilled position
        List<Long> backfilled = jdbc.getJdbcTemplate().queryForList("SELECT chain_position FROM " + schema
                + ".admin_audit_events WHERE action LIKE 'PRE_%' " + "ORDER BY chain_position", Long.class);
        for (Long bp : backfilled) {
            assertThat(postPosition).as("post-V3 position %d > backfilled %d", postPosition, bp).isGreaterThan(bp);
        }
    }

    // -------------------------------------------------------------------
    // 3. Empty-table upgrade — deterministic sequence start
    // -------------------------------------------------------------------

    @Test
    @DisplayName("empty-table V2→V3 upgrade: first post-V3 insert receives chain_position 1")
    void emptyTableUpgradeFirstInsertGetsPosition1() {
        String schema = createSchema("v3up_empty");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        // Insert a single row using the column DEFAULT
        UUID newId = UUID.randomUUID();
        jdbc.getJdbcTemplate()
                .update("INSERT INTO " + schema + ".admin_audit_events "
                        + "(id, tenant_id, actor_id, action, target_type, change_summary, current_event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", newId, SEED_TENANT_ID, UUID.randomUUID(), "FIRST",
                        "TYPE", "0", new byte[32]);

        Long position = jdbc.getJdbcTemplate().queryForObject(
                "SELECT chain_position FROM " + schema + ".admin_audit_events WHERE id = ?", Long.class, newId);
        assertThat(position).as("first post-V3 insert on empty table gets position 1").isEqualTo(1L);
    }

    @Test
    @DisplayName("empty-table V2→V3 upgrade: nextval directly returns 1 (no sharing, no order-dependency)")
    void emptyTableUpgradeNextvalReturns1() {
        String schema = createSchema("v3up_nv1");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        Long nextVal = jdbc.getJdbcTemplate()
                .queryForObject("SELECT nextval('" + schema + ".admin_audit_events_chain_seq')", Long.class);
        assertThat(nextVal).as("first nextval on empty table in isolated schema returns 1").isEqualTo(1L);
    }

    // -------------------------------------------------------------------
    // 4. NOT NULL constraint after V3
    // -------------------------------------------------------------------

    @Test
    @DisplayName("chain_position column is NOT NULL after V3 upgrade")
    void chainPositionIsNotNullAfterV3() {
        String schema = createSchema("v3up_notnull");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        String nullable = jdbc.getJdbcTemplate()
                .queryForObject("SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'admin_audit_events' "
                        + "AND column_name = 'chain_position'", String.class, schema);
        assertThat(nullable).as("chain_position must be NOT NULL").isEqualTo("NO");
    }

    @Test
    @DisplayName("insert without chain_position should succeed via column DEFAULT after V3")
    void insertWithoutChainPositionSucceedsAfterV3() {
        String schema = createSchema("v3up_defok");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        // Insert omitting chain_position — must succeed via DEFAULT nextval(...)
        assertThatCode(() -> jdbc.getJdbcTemplate()
                .update("INSERT INTO " + schema + ".admin_audit_events "
                        + "(id, tenant_id, actor_id, action, target_type, change_summary, current_event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", UUID.randomUUID(), SEED_TENANT_ID, UUID.randomUUID(),
                        "DEF", "TYPE", "0", new byte[32]))
                .as("insert without chain_position must succeed via DEFAULT").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("insert with explicit NULL chain_position must be rejected after V3")
    void insertWithNullChainPositionRejectedAfterV3() {
        String schema = createSchema("v3up_nullrej");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        // Insert with explicit NULL — MUST fail because the column is NOT NULL
        // and the DEFAULT is suppressed when NULL is explicit
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.getJdbcTemplate().update(
                        "INSERT INTO " + schema + ".admin_audit_events "
                                + "(id, tenant_id, actor_id, action, target_type, change_summary, "
                                + "current_event_hash, chain_position) " + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NULL)",
                        UUID.randomUUID(), SEED_TENANT_ID, UUID.randomUUID(), "NULLPOS", "TYPE", "0", new byte[32]));
    }

    // -------------------------------------------------------------------
    // 5. UNIQUE constraint after V3
    // -------------------------------------------------------------------

    @Test
    @DisplayName("chain_position has a UNIQUE constraint after V3 upgrade")
    void chainPositionHasUniqueConstraintAfterV3() {
        String schema = createSchema("v3up_unique");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        // Verify the unique constraint exists by name
        Integer count = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE conrelid = '" + schema + ".admin_audit_events'::regclass "
                + "AND contype = 'u' AND conname = 'uq_admin_audit_events_chain_position'", Integer.class);
        assertThat(count).as("unique constraint uq_admin_audit_events_chain_position must exist").isEqualTo(1);
    }

    @Test
    @DisplayName("duplicate chain_position is rejected after V3 upgrade")
    void duplicateChainPositionRejectedAfterV3() {
        String schema = createSchema("v3up_duprej");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        // Insert first row (gets chain_position via DEFAULT)
        UUID actorId = UUID.randomUUID();
        jdbc.getJdbcTemplate()
                .update("INSERT INTO " + schema + ".admin_audit_events "
                        + "(id, tenant_id, actor_id, action, target_type, change_summary, current_event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", UUID.randomUUID(), SEED_TENANT_ID, actorId, "FIRST",
                        "TYPE", "1", new byte[32]);

        // Try to insert a second row with an explicit chain_position = 1 (duplicate)
        org.junit.jupiter.api.Assertions
                .assertThrows(org.springframework.dao.DuplicateKeyException.class,
                        () -> jdbc.getJdbcTemplate().update("INSERT INTO " + schema + ".admin_audit_events "
                                + "(id, tenant_id, actor_id, action, target_type, change_summary, "
                                + "current_event_hash, chain_position) " + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 1)",
                                UUID.randomUUID(), SEED_TENANT_ID, actorId, "DUP", "TYPE", "2", new byte[32]));
    }

    // -------------------------------------------------------------------
    // 6. Sequence OWNED BY after V3
    // -------------------------------------------------------------------

    @Test
    @DisplayName("sequence is OWNED BY chain_position column after V3 upgrade")
    void sequenceOwnedByChainPositionAfterV3() {
        String schema = createSchema("v3up_owned");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        // Verify OWNED BY via pg_depend with schema-qualified table
        Boolean owned = jdbc.getJdbcTemplate().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_depend d
                    JOIN pg_class c ON d.objid = c.oid
                    JOIN pg_class t ON d.refobjid = t.oid
                    JOIN pg_attribute a ON d.refobjid = a.attrelid AND d.refobjsubid = a.attnum
                    JOIN pg_namespace ns ON t.relnamespace = ns.oid
                    WHERE c.relname = 'admin_audit_events_chain_seq'
                      AND c.relnamespace = ns.oid
                      AND t.relname = 'admin_audit_events'
                      AND t.relnamespace = ns.oid
                      AND a.attname = 'chain_position'
                      AND ns.nspname = ?
                      AND d.deptype = 'a'
                )
                """, Boolean.class, schema);
        assertThat(owned).as("sequence must be owned by chain_position in schema " + schema).isTrue();
    }

    // -------------------------------------------------------------------
    // 7. Sequence default on column after V3
    // -------------------------------------------------------------------

    @Test
    @DisplayName("chain_position column_default references the sequence after V3 upgrade")
    void chainPositionDefaultReferencesSequenceAfterV3() {
        String schema = createSchema("v3up_cdef");

        migrateTo(schema, "2");
        migrateTo(schema, "3");

        String columnDefault = jdbc.getJdbcTemplate()
                .queryForObject("SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'admin_audit_events' "
                        + "AND column_name = 'chain_position'", String.class, schema);
        assertThat(columnDefault).as("column_default must reference the sequence").contains("nextval")
                .contains("admin_audit_events_chain_seq");
    }

    // -------------------------------------------------------------------
    // 8. data integrity: backfill preserves original row data
    // -------------------------------------------------------------------

    @Test
    @DisplayName("V2→V3 backfill does not alter existing row data")
    void backfillDoesNotAlterExistingRowData() {
        String schema = createSchema("v3up_intact");

        migrateTo(schema, "2");

        UUID rowId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String action = "ORIGINAL_ACTION";
        String targetType = "ORIGINAL_TYPE";
        byte[] hash = new byte[32];
        hash[0] = 0x42; // recognizable value

        jdbc.getJdbcTemplate()
                .update("INSERT INTO " + schema + ".admin_audit_events "
                        + "(id, tenant_id, actor_id, action, target_type, change_summary, current_event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", rowId, SEED_TENANT_ID, actorId, action, targetType,
                        "42", hash);

        migrateTo(schema, "3");

        // Read back the same row and verify all original fields
        Map<String, Object> row = jdbc.getJdbcTemplate()
                .queryForMap("SELECT * FROM " + schema + ".admin_audit_events WHERE id = ?", rowId);

        assertThat(row.get("action")).as("action preserved").isEqualTo(action);
        assertThat(row.get("target_type")).as("target_type preserved").isEqualTo(targetType);
        assertThat(row.get("tenant_id")).as("tenant_id preserved").isEqualTo(SEED_TENANT_ID);
        assertThat(row.get("actor_id")).as("actor_id preserved").isEqualTo(actorId);
        assertThat(row.get("chain_position")).as("chain_position assigned").isNotNull();
    }
}
