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
     * Groups every non-primary index of the public schema by everything that makes
     * one index redundant with another, and returns the groups holding more than
     * one index.
     * <p>
     * Two indexes are only interchangeable when they agree on the whole key
     * definition, so the grouping covers each catalog attribute that changes which
     * queries an index can serve:
     * <ul>
     * <li>{@code indkey} + {@code indnkeyatts} — key columns in order, kept
     * separate from any {@code INCLUDE} payload columns;</li>
     * <li>{@code indexprs} — the expression trees. Column positions in
     * {@code indkey} are rendered as {@code 0} for an expression, so without this
     * an index on {@code lower(username)} and one on {@code upper(username)} would
     * look identical. {@code users} already carries an expression index (V1:61), so
     * the case is live;</li>
     * <li>{@code indclass} — operator class, so a {@code text_pattern_ops}
     * companion index (which serves {@code LIKE 'prefix%'}) is not treated as a
     * duplicate of the default-opclass index;</li>
     * <li>{@code indcollation} — collation;</li>
     * <li>{@code indoption} — per-column {@code DESC} / {@code NULLS FIRST};</li>
     * <li>{@code indisunique} and the partial-index predicate.</li>
     * </ul>
     * Without these, a legitimate expression, ordering or opclass variant would be
     * reported as redundant and fail the build for no reason.
     * <p>
     * Known limits, deliberately not covered:
     * <ul>
     * <li>{@code indisunique} is part of the key, so a plain index whose columns
     * merely repeat a unique index's columns is <em>not</em> reported. Serving
     * equal lookups twice is wasteful too, but the unique index is doing
     * enforcement work the plain one is not, so folding them together would need a
     * judgement call per case.</li>
     * <li>Reloptions ({@code fillfactor}, {@code deduplicate_items}) are not
     * compared, so two indexes differing only in storage parameters would be
     * reported. No index in this schema currently sets any.</li>
     * <li>Only the {@code public} schema is scanned, and only at partition parents
     * ({@code relispartition} copies excluded), so DDL applied out of band to an
     * individual partition is out of scope.</li>
     * </ul>
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
                     i.indnkeyatts,
                     COALESCE(pg_get_expr(i.indexprs, i.indrelid), ''),
                     i.indclass::text,
                     i.indcollation::text,
                     i.indoption::text,
                     COALESCE(pg_get_expr(i.indpred, i.indrelid), ''),
                     i.indisunique
            HAVING count(*) > 1
            ORDER BY 1
            """;

    @Test
    @DisplayName("should not define two indexes with identical columns and predicate on one table")
    void shouldNotDefineRedundantIndexes() {
        List<String> duplicates = jdbc.queryForList(DUPLICATE_INDEX_SQL, Map.of(), String.class);

        assertThat(duplicates).as("Redundant indexes (identical key definition, uniqueness and predicate). "
                + "Every duplicate is a b-tree maintained for nothing on each write.").isEmpty();
    }
}
