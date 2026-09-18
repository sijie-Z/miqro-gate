package com.miqroera.miqrokey.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.persistence.repository.PriceSnapshotSql;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Price lookup must be deterministic when two rows share an
 * {@code effective_from} (#710).
 *
 * <p>
 * This is not a theoretical concern: the historical backfill replays prices by
 * {@code occurred_at}, so an ambiguous lookup makes the replay itself
 * non-reproducible — the same window could reconstruct different numbers on
 * different runs, and no one could say which was right.
 * </p>
 *
 * <p>
 * The old {@code findAllLatestAt} joined on {@code MAX(effective_from)} and
 * therefore returned <em>every</em> row sharing the max timestamp; the caller
 * folds them into a map, so the winner was whatever the database returned last.
 * </p>
 *
 * <p>
 * Rows are namespaced by a per-instance UUID and cleaned up by id rather than
 * by truncating shared tables: a whole-table DELETE reached into state other
 * test classes own and, in the full suite, tripped their foreign keys.
 * </p>
 */
@DisplayName("Price snapshot lookup determinism (#710)")
class PriceSnapshotDeterminismTest extends AbstractPostgresTest {

    private static final String MODEL = "determinism-model";

    /** Same instant for both rows — the whole point of this test. */
    private static final Instant SAME_EFFECTIVE_FROM = Instant.parse("2026-09-01T00:00:00Z");

    /** Ordered so Postgres uuid comparison is unambiguous (…a1 < …a2). */
    private static final UUID LOWER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID HIGHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    /**
     * Fresh per test instance (JUnit builds one per method), so this class can only
     * ever collide with its own rows.
     */
    private final UUID provider = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    @Autowired
    PriceSnapshotRepository repository;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:id, :slug, 'Determinism Provider', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", provider).addValue("slug", "det-" + provider));
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:id, :providerId, :code, 'Determinism Product', 'PAYG', 'SINGLE_SHARED',
                        '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                """, new MapSqlParameterSource("id", product).addValue("providerId", provider).addValue("code",
                "det-" + product));

        // Two rows, identical (product, model, token_type, effective_from) — only the
        // id differs.
        insertPrice(LOWER_ID, "1.00");
        insertPrice(HIGHER_ID, "2.00");
    }

    @AfterEach
    void clean() {
        // Child-first, and scoped to this instance's own rows.
        jdbc.update("DELETE FROM price_snapshot WHERE provider_product_id = :id",
                new MapSqlParameterSource("id", product));
        jdbc.update("DELETE FROM provider_products WHERE id = :id", new MapSqlParameterSource("id", product));
        jdbc.update("DELETE FROM providers WHERE id = :id", new MapSqlParameterSource("id", provider));
    }

    @Test
    @DisplayName("findLatestAt picks the same row every time when effective_from ties")
    void findLatestAtIsDeterministicOnTies() {
        Instant queryAt = SAME_EFFECTIVE_FROM.plusSeconds(60);

        BigDecimal first = repository.findLatestAt(product, MODEL, PriceTokenType.INPUT, queryAt)
                .map(PriceSnapshot::unitPrice).orElseThrow();

        // Repeat: with effective_from alone the winner was left to the database.
        for (int i = 0; i < 5; i++) {
            BigDecimal again = repository.findLatestAt(product, MODEL, PriceTokenType.INPUT, queryAt)
                    .map(PriceSnapshot::unitPrice).orElseThrow();
            assertThat(again).as("repeat %d must not flip", i).isEqualByComparingTo(first);
        }
        assertThat(first).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    @DisplayName("findAllLatestAt returns exactly one row per triple, not every tied row")
    void findAllLatestAtReturnsOneRowPerTriple() {
        List<PriceSnapshot> rows = repository.findAllLatestAt(SAME_EFFECTIVE_FROM.plusSeconds(60));

        List<PriceSnapshot> forThisTriple = rows.stream().filter(p -> product.equals(p.providerProductId())
                && MODEL.equals(p.modelId()) && p.tokenType() == PriceTokenType.INPUT).toList();

        // The old MAX(effective_from) join returned both; the caller's map put() then
        // made the winner depend on row order.
        assertThat(forThisTriple).as("exactly one winner per (product, model, token type)").hasSize(1);
        assertThat(forThisTriple.get(0).unitPrice()).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    @DisplayName("a strictly later effective_from still wins over the id tie-break")
    void laterEffectiveFromOutranksTheIdTieBreak() {
        // A smaller id but a later timestamp must win: id only breaks exact ties.
        insertPrice(UUID.fromString("00000000-0000-0000-0000-0000000000a0"), "3.00",
                SAME_EFFECTIVE_FROM.plusSeconds(3600));

        BigDecimal price = repository
                .findLatestAt(product, MODEL, PriceTokenType.INPUT, SAME_EFFECTIVE_FROM.plusSeconds(7200))
                .map(PriceSnapshot::unitPrice).orElseThrow();

        assertThat(price).isEqualByComparingTo(new BigDecimal("3.00"));
    }

    @Test
    @DisplayName("a price effective after the query instant is not visible")
    void futureEffectiveFromIsNotUsed() {
        insertPrice(UUID.fromString("00000000-0000-0000-0000-0000000000a3"), "9.99",
                SAME_EFFECTIVE_FROM.plusSeconds(3600));

        // Query between the tie and the later row: the tie rows are the latest visible.
        BigDecimal price = repository
                .findLatestAt(product, MODEL, PriceTokenType.INPUT, SAME_EFFECTIVE_FROM.plusSeconds(60))
                .map(PriceSnapshot::unitPrice).orElseThrow();

        assertThat(price).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    @DisplayName("the shared as-of helper agrees with the repository's point lookup")
    void asOfHelperAgreesWithRepository() {
        // PriceSnapshotSql is a second expression of the same rule the repository
        // implements,
        // and the cost aggregates depend on it. This is what keeps the two honest — a
        // comment
        // asking future readers to be careful would not.
        String sql = "SELECT " + PriceSnapshotSql.asOfUnitPrice(PriceTokenType.INPUT, ":productId", ":modelId", ":at")
                + " AS unit_price";
        Instant at = SAME_EFFECTIVE_FROM.plusSeconds(60);
        BigDecimal viaSql = jdbc.queryForObject(sql, new MapSqlParameterSource("productId", product)
                .addValue("modelId", MODEL).addValue("at", java.sql.Timestamp.from(at)), BigDecimal.class);

        BigDecimal viaRepository = repository.findLatestAt(product, MODEL, PriceTokenType.INPUT, at)
                .map(PriceSnapshot::unitPrice).orElseThrow();

        assertThat(viaSql).isEqualByComparingTo(viaRepository);
        assertThat(viaSql).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    private void insertPrice(UUID id, String unitPrice) {
        insertPrice(id, unitPrice, SAME_EFFECTIVE_FROM);
    }

    private void insertPrice(UUID id, String unitPrice, Instant effectiveFrom) {
        jdbc.update("""
                INSERT INTO price_snapshot
                    (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source)
                VALUES (:id, :productId, :modelId, 'INPUT', 'CNY', :unitPrice, :effectiveFrom, 'MANUAL')
                """,
                new MapSqlParameterSource("id", id).addValue("productId", product).addValue("modelId", MODEL)
                        .addValue("unitPrice", new BigDecimal(unitPrice))
                        .addValue("effectiveFrom", java.sql.Timestamp.from(effectiveFrom)));
    }
}
