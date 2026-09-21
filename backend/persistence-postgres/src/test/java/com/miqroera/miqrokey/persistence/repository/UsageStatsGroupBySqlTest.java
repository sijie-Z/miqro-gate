package com.miqroera.miqrokey.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.GroupBy;
import com.miqroera.miqrokey.domain.repository.UsageStatsRepository.UsageFilter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The GROUP BY clauses the summary repository composes (#1200).
 *
 * <p>
 * The hits SQL used to carry a bare {@code 'HIT'} literal as the group-by
 * fragment of the cache-level dimension. PostgreSQL reads a bare literal in
 * GROUP BY as a positional reference and rejects it ({@code non-integer
 * constant in GROUP BY}), so every {@code groupBy=cache_level} request was a
 * 500. The fix gives the fragment the empty string and lets the template drop
 * the {@code ", "} separator with it.
 * </p>
 *
 * <p>
 * The assertions below run the real composition path (the repository, against a
 * mocked template that records the SQL it is handed) and pin the two halves of
 * that contract: the cache-level hits clause is exactly the four key columns —
 * no leading fragment, no literal — while every other hits dimension keeps its
 * own fragment joined to the key columns by {@code ", "}. The usage-event side
 * is pinned separately: it groups cache-level by the real
 * {@code ue.cache_level} column and is not part of the empty-fragment
 * mechanism.
 * </p>
 */
@DisplayName("Summary SQL: hits GROUP BY carries no bare literal, every dimension keeps its fragment (#1200)")
class UsageStatsGroupBySqlTest {

    /** Seed tenant from V1 — the filter needs one; no row is ever read. */
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    @DisplayName("cache-level hits group only by the key columns — the 'HIT' literal is gone")
    void cacheLevelHitsGroupByCarriesNoLiteral() {
        assertThat(hitsGroupByClause(GroupBy.CACHE_LEVEL))
                .isEqualTo("GROUP BY e.provider_product_id, e.model_id, e.cache_key, e.meta_json");
    }

    @Test
    @DisplayName("every other hits dimension keeps its fragment joined to the four key columns by ', '")
    void everyHitsDimensionKeepsItsFragment() {
        for (GroupBy dimension : GroupBy.values()) {
            if (dimension == GroupBy.CACHE_LEVEL) {
                // Pinned exactly by cacheLevelHitsGroupByCarriesNoLiteral: no
                // fragment at all, so no leading ", " either.
                continue;
            }
            assertThat(hitsGroupByClause(dimension)).as("%s hits GROUP BY", dimension).startsWith("GROUP BY ")
                    // The comma is what a broken fragment join would eat (either
                    // glued to the fragment or replaced by a double space); the
                    // clause must still separate the fragment from the keys by
                    // exactly ", ".
                    .endsWith(", e.provider_product_id, e.model_id, e.cache_key, e.meta_json");
        }
    }

    @Test
    @DisplayName("the usage-event side keeps grouping cache-level by the real column")
    void cacheLevelEventsGroupByTheRealColumn() {
        assertThat(eventsGroupByClause(GroupBy.CACHE_LEVEL))
                .isEqualTo("GROUP BY ue.cache_level, ue.provider_product_id, ue.model_id, ue.cache_level");
    }

    // ------------------------------------------------------------------

    /**
     * The hits GROUP BY clause (without the trailing newline) for one dimension.
     */
    private static String hitsGroupByClause(GroupBy dimension) {
        return groupByClause(captureSql(dimension, true));
    }

    /** The usage-event GROUP BY clause (without the trailing newline). */
    private static String eventsGroupByClause(GroupBy dimension) {
        return groupByClause(captureSql(dimension, false));
    }

    /**
     * Runs the real repository aggregation against a mocked template and returns
     * the SQL it issued. The mock never invokes the row callback, so no row is
     * parsed and no database is needed.
     */
    private static String captureSql(GroupBy dimension, boolean hits) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        UsageStatsRepositoryImpl repository = new UsageStatsRepositoryImpl(jdbc, new ObjectMapper());
        UsageFilter filter = new UsageFilter(TENANT, null, FROM, TO);
        if (hits) {
            repository.aggregateHits(dimension, filter, 0);
        } else {
            repository.aggregateUsage(dimension, filter, 0);
        }
        String sql = captured.get();
        assertThat(sql).as("%s SQL was issued", dimension).isNotNull();
        return sql;
    }

    /** The single GROUP BY clause of a composed query. */
    private static String groupByClause(String sql) {
        int start = sql.lastIndexOf("GROUP BY ");
        assertThat(start).as("a GROUP BY in the composed SQL").isNotNegative();
        int end = sql.indexOf('\n', start);
        return sql.substring(start, end < 0 ? sql.length() : end);
    }
}
