package com.miqroera.miqrokey.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Flyway schema against redundant index definitions.
 * <p>
 * PostgreSQL will happily create two indexes with an identical column list and
 * predicate on the same table under different names. Nothing fails and no query
 * gets slower in a way a test can see — but every INSERT on that table now
 * maintains both b-trees, and both accumulate bloat and autovacuum work. That
 * is exactly the kind of thing that only shows up once the table is large, so
 * it has to be caught at the schema level instead.
 * <p>
 * Indexes on partitioned parents are checked once at the parent; the per
 * partition copies are created automatically and are excluded via
 * {@code relispartition}.
 */
@DisplayName("Index hygiene")
class IndexHygieneTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    /**
     * Groups every non-primary index of the public schema by the exact thing
     * that makes it redundant — table, key columns (in order), uniqueness and
     * partial-index predicate — and returns the groups that hold more than one
     * index. No index in this schema uses INCLUDE, so {@code indkey} is the
     * full key column list.
     */
    private static final String DUPLICATE_INDEX_SQL = """
            SELECT c.relname || ' :: ' || array_agg(ic.relname ORDER BY ic.relname)::text AS dup
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indrelid
            JOIN pg_class ic ON ic.oid = i.indexrelid
            WHERE c.relnamespace = 'public'::regnamespace
              AND c.relkind IN ('r', 'p')
              AND NOT c.relispartition
              AND NOT i.indisprimary
            GROUP BY c.relname,
                     i.indkey::text,
                     COALESCE(pg_get_expr(i.indpred, i.indrelid), ''),
                     i.indisunique
            HAVING count(*) > 1
            ORDER BY 1
            """;

    @Test
    @DisplayName("should not define two indexes with identical columns and predicate on one table")
    void shouldNotDefineRedundantIndexes() {
        List<String> duplicates = jdbc.queryForList(DUPLICATE_INDEX_SQL, Map.of(), String.class);

        assertThat(duplicates)
                .as("Redundant indexes (identical table + key columns + predicate). "
                        + "Each duplicate doubles index write cost and bloat on every INSERT.")
                .isEmpty();
    }
}
