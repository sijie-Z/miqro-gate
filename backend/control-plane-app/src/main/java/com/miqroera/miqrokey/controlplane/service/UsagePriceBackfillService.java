package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsagePriceBackfillResult;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.UsageStatsAggregator;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes the price basis onto existing usage rows (#710 / F21-A).
 *
 * <p>
 * Cost used to be computed at read time from <em>current</em> prices, so
 * editing a price changed the cost of history. This job stamps each historical
 * row with the prices that were in effect <b>when the event occurred</b>
 * ({@code price_snapshot.effective_from <= usage_event.occurred_at}), which is
 * what makes reported history stable and replayable.
 * </p>
 *
 * <p>
 * The distinction that matters most is <b>"unknown" vs "free"</b>: a row whose
 * price cannot be reconstructed is marked {@code UNAVAILABLE} with the price
 * columns left NULL. It is never written as a zero unit price — "we could not
 * determine the price" and "this was free" are different audit facts, and
 * collapsing them would silently understate historical spend.
 * </p>
 *
 * <p>
 * Rows are selected by {@code price_status IS NULL} ("not yet evaluated"), so
 * the job is idempotent and re-runnable: a second pass over the same window
 * finds nothing to do. It deliberately does <em>not</em> re-price rows that
 * already carry a status, including {@code UNAVAILABLE} ones — re-running must
 * not be able to revise a decision that was already made.
 * </p>
 *
 * <p>
 * Two further passes bring already-stamped rows up to date, because the
 * stamping pass can never revisit one: one completes a {@code base_cost_amount}
 * that was never written (#771), the other brings a verdict the rule has since
 * moved past back in line (#777). Both derive from the prices the row already
 * froze — no price lookup, no amount rewritten.
 * </p>
 */
@Service
public class UsagePriceBackfillService {

    private static final Logger LOG = LoggerFactory.getLogger(UsagePriceBackfillService.class);

    private static final Duration MAX_WINDOW = Duration.ofDays(93);
    private static final int BATCH = 500;

    /**
     * Prices as of each row's own {@code occurred_at}.
     *
     * <p>
     * The tie-break ({@code effective_from DESC, id DESC}) is the same rule
     * {@code PriceSnapshotRepositoryImpl} applies. Expressing it twice is a real
     * drift risk, so {@code UsagePriceBackfillIntegrationTest} cross-checks this
     * statement's verdict against the repository for the same rows — the test, not
     * a comment, is what keeps the two honest.
     * </p>
     */
    private static final String SELECT_BATCH = """
            WITH targets AS (
                SELECT ue.id, ue.provider_product_id, ue.model_id, ue.occurred_at,
                       COALESCE(ue.input_tokens, ue.prompt_tokens) AS input_tokens,
                       COALESCE(ue.output_tokens, ue.completion_tokens) AS output_tokens,
                       ue.cache_read_input_tokens, ue.cache_creation_input_tokens
                  FROM usage_event ue
                 WHERE ue.tenant_id = :tenantId
                   AND ue.occurred_at >= :from AND ue.occurred_at < :to
                   AND ue.price_status IS NULL
                 ORDER BY ue.occurred_at, ue.id
                 LIMIT :limit
            )
            SELECT t.id,
                   t.input_tokens, t.output_tokens, t.cache_read_input_tokens, t.cache_creation_input_tokens,
                   i.unit_price  AS price_input,
                   o.unit_price  AS price_output,
                   cr.unit_price AS price_cache_read,
                   cc.unit_price AS price_cache_creation,
                   COALESCE(i.currency, o.currency, cr.currency, cc.currency)                  AS price_currency,
                   COALESCE(i.source, o.source, cr.source, cc.source)                          AS price_source,
                   COALESCE(i.effective_from, o.effective_from, cr.effective_from,
                            cc.effective_from)                                                 AS price_effective_from
              FROM targets t
              LEFT JOIN LATERAL (SELECT ps.unit_price, ps.currency, ps.source, ps.effective_from
                                   FROM price_snapshot ps
                                  WHERE ps.provider_product_id = t.provider_product_id
                                    AND ps.model_id = t.model_id AND ps.token_type = 'INPUT'
                                    AND ps.effective_from <= t.occurred_at
                                  ORDER BY ps.effective_from DESC, ps.id DESC LIMIT 1) i ON TRUE
              LEFT JOIN LATERAL (SELECT ps.unit_price, ps.currency, ps.source, ps.effective_from
                                   FROM price_snapshot ps
                                  WHERE ps.provider_product_id = t.provider_product_id
                                    AND ps.model_id = t.model_id AND ps.token_type = 'OUTPUT'
                                    AND ps.effective_from <= t.occurred_at
                                  ORDER BY ps.effective_from DESC, ps.id DESC LIMIT 1) o ON TRUE
              LEFT JOIN LATERAL (SELECT ps.unit_price, ps.currency, ps.source, ps.effective_from
                                   FROM price_snapshot ps
                                  WHERE ps.provider_product_id = t.provider_product_id
                                    AND ps.model_id = t.model_id AND ps.token_type = 'CACHE_READ'
                                    AND ps.effective_from <= t.occurred_at
                                  ORDER BY ps.effective_from DESC, ps.id DESC LIMIT 1) cr ON TRUE
              LEFT JOIN LATERAL (SELECT ps.unit_price, ps.currency, ps.source, ps.effective_from
                                   FROM price_snapshot ps
                                  WHERE ps.provider_product_id = t.provider_product_id
                                    AND ps.model_id = t.model_id AND ps.token_type = 'CACHE_CREATION'
                                    AND ps.effective_from <= t.occurred_at
                                  ORDER BY ps.effective_from DESC, ps.id DESC LIMIT 1) cc ON TRUE
            """;

    /**
     * The four priced dimensions, as (price column key, token column key) pairs.
     *
     * <p>
     * One list, used by both the status decision and the base-cost sum, because the
     * rule it encodes is easy to get subtly wrong in two places at once: <b>a
     * dimension takes part only if the event actually carries tokens for it.</b> An
     * event with no cache_creation tokens is COMPLETE even though we hold no
     * cache_creation price — that dimension never entered the calculation. Judging
     * by price availability alone (which this used to do) marked almost every row
     * PARTIAL on a catalogue that simply has no cache_creation price, including
     * rows that never touched that dimension.
     * </p>
     */
    private static final List<Map.Entry<String, String>> DIMENSIONS = List.of(Map.entry("input", "inputTokens"),
            Map.entry("output", "outputTokens"), Map.entry("cacheRead", "cacheReadTokens"),
            Map.entry("cacheCreation", "cacheCreationTokens"));

    private static final String UPDATE_ROW = """
            UPDATE usage_event
               SET price_input = :input, price_output = :output, price_cache_read = :cacheRead,
                   price_cache_creation = :cacheCreation, price_currency = :currency,
                   price_effective_from = :effectiveFrom, price_source = :source, price_status = :status,
                   base_cost_amount = :baseCost
             WHERE id = :id AND tenant_id = :tenantId AND price_status IS NULL
            """;

    /**
     * Rows that were stamped before {@code base_cost_amount} existed (#771).
     *
     * <p>
     * V66 added the column, but the stamping pass only selects rows with
     * {@code price_status IS NULL} — so everything evaluated earlier kept a NULL
     * base cost, and no number of re-runs could repair it. Those rows are exactly
     * what this pass targets: already stamped, still missing their amount.
     * </p>
     *
     * <p>
     * The predicate mirrors {@link #baseCost} on purpose, and has to: a row is
     * derivable exactly when some dimension both carries tokens and holds a frozen
     * price. An over-inclusive selection would be <em>unsound</em> rather than
     * merely wasteful — {@code LIMIT} plus {@code ORDER BY} would let underivable
     * rows fill a batch, and the pass would stop with derivable rows still stranded
     * behind them. {@code UNAVAILABLE} rows are excluded by status rather than by a
     * sentinel: their NULL is a recorded fact, not an omission.
     * </p>
     */
    private static final String SELECT_BASE_COST_BATCH = """
            SELECT ue.id,
                   COALESCE(ue.input_tokens, ue.prompt_tokens)      AS input_tokens,
                   COALESCE(ue.output_tokens, ue.completion_tokens) AS output_tokens,
                   ue.cache_read_input_tokens, ue.cache_creation_input_tokens,
                   ue.price_input, ue.price_output, ue.price_cache_read, ue.price_cache_creation,
                   ue.price_currency, ue.price_source, ue.price_effective_from
              FROM usage_event ue
             WHERE ue.tenant_id = :tenantId
               AND ue.occurred_at >= :from AND ue.occurred_at < :to
               AND ue.price_status IN ('COMPLETE', 'PARTIAL')
               AND ue.base_cost_amount IS NULL
               AND ((COALESCE(ue.input_tokens, ue.prompt_tokens, 0) <> 0 AND ue.price_input IS NOT NULL)
                    OR (COALESCE(ue.output_tokens, ue.completion_tokens, 0) <> 0 AND ue.price_output IS NOT NULL)
                    OR (COALESCE(ue.cache_read_input_tokens, 0) <> 0 AND ue.price_cache_read IS NOT NULL)
                    OR (COALESCE(ue.cache_creation_input_tokens, 0) <> 0 AND ue.price_cache_creation IS NOT NULL))
             ORDER BY ue.occurred_at, ue.id
             LIMIT :limit
            """;

    /**
     * Completes the base cost of an already-evaluated row (#771).
     *
     * <p>
     * It writes <b>only</b> {@code base_cost_amount}. The frozen prices and the
     * status are a decision already on the record, and this pass must not be able
     * to revise one — it exists because the column was added after those decisions
     * were made. {@code base_cost_amount IS NULL} in the predicate is what keeps
     * the statement idempotent.
     * </p>
     */
    private static final String FILL_BASE_COST = """
            UPDATE usage_event
               SET base_cost_amount = :baseCost
             WHERE id = :id AND tenant_id = :tenantId AND base_cost_amount IS NULL
            """;

    /**
     * Already-stamped rows in the window, walked with a keyset cursor (#777).
     *
     * <p>
     * This pass is a <b>scan</b>, not a work queue. Whether a row's stored verdict
     * is stale depends on the rule in force <em>now</em>, which only
     * {@link #classify} knows — so the pass has to look at every stamped row rather
     * than ask SQL for "the ones that need work". That rules out the
     * select-work/re-query shape the other two passes use: with a {@code LIMIT} and
     * no cursor, a page of already-correct rows ends the scan and strands the stale
     * ones behind them — the same trap #771 hit, one level up.
     * </p>
     *
     * <p>
     * The stored verdict is selected alongside the frozen basis because the
     * comparison needs both; the decision itself is still made in Java, so the rule
     * stays in exactly one place.
     * </p>
     */
    private static final String RECLASSIFY_BATCH = """
            SELECT ue.id,
                   COALESCE(ue.input_tokens, ue.prompt_tokens)      AS input_tokens,
                   COALESCE(ue.output_tokens, ue.completion_tokens) AS output_tokens,
                   ue.cache_read_input_tokens, ue.cache_creation_input_tokens,
                   ue.price_input, ue.price_output, ue.price_cache_read, ue.price_cache_creation,
                   ue.price_currency, ue.price_source, ue.price_effective_from,
                   ue.price_status, ue.occurred_at
              FROM usage_event ue
             WHERE ue.tenant_id = :tenantId
               AND ue.occurred_at >= :from AND ue.occurred_at < :to
               AND ue.price_status IS NOT NULL
               AND (ue.occurred_at > :cursorAt OR (ue.occurred_at = :cursorAt AND ue.id > :cursorId))
             ORDER BY ue.occurred_at, ue.id
             LIMIT :limit
            """;

    /**
     * Rewrites one stored verdict (#777).
     *
     * <p>
     * It sets <b>only</b> {@code price_status}. The frozen prices and
     * {@code base_cost_amount} are facts about the event and stay untouched: a
     * verdict is a <em>derivation</em> from those facts, which is the whole reason
     * it can be recomputed — whereas the amount is a fact that only ever gets
     * <em>filled</em>, never rewritten (#771). {@code IS DISTINCT FROM} keeps the
     * statement idempotent and stops it churning rows the current rule already
     * agrees with.
     * </p>
     */
    private static final String RECLASSIFY_ROW = """
            UPDATE usage_event
               SET price_status = :status
             WHERE id = :id AND tenant_id = :tenantId AND price_status IS DISTINCT FROM :status
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;

    public UsagePriceBackfillService(NamedParameterJdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    /**
     * Stamps the frozen price basis onto every not-yet-evaluated row in the window.
     *
     * @throws ApiException
     *             {@code BAD_REQUEST} for an invalid or overly wide window
     */
    @Transactional
    public UsagePriceBackfillResult backfill(UUID tenantId, UUID adminId, Instant from, Instant to, String requestId) {
        validateWindow(from, to);
        long complete = 0;
        long partial = 0;
        long unavailable = 0;
        long scanned = 0;

        while (true) {
            List<Map<String, Object>> batch = jdbc.query(SELECT_BATCH, window(tenantId, from, to),
                    UsagePriceBackfillService::priceRow);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : batch) {
                String status = classify(row);
                if ("COMPLETE".equals(status)) {
                    complete++;
                } else if ("PARTIAL".equals(status)) {
                    partial++;
                } else {
                    unavailable++;
                }
                stamp(tenantId, row, status);
                scanned++;
            }
        }
        long reclassified = reclassifyStampedRows(tenantId, from, to);
        long baseCostFilled = fillBaseCost(tenantId, from, to);

        UsagePriceBackfillResult result = new UsagePriceBackfillResult(scanned, complete, partial, unavailable,
                baseCostFilled, reclassified);
        LOG.info(
                "Usage price backfill {}..{}: scanned={} complete={} partial={} unavailable={} baseCostFilled={} "
                        + "reclassified={}",
                from, to, result.scanned(), result.complete(), result.partial(), result.unavailable(),
                result.baseCostFilled(), result.reclassified());
        auditService.record(tenantId, adminId, "USAGE_PRICE_BACKFILL", "USAGE_EVENT", null,
                "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"scanned\":" + result.scanned() + ",\"complete\":"
                        + result.complete() + ",\"partial\":" + result.partial() + ",\"unavailable\":"
                        + result.unavailable() + ",\"baseCostFilled\":" + result.baseCostFilled() + ",\"reclassified\":"
                        + result.reclassified() + "}",
                requestId);
        return result;
    }

    /**
     * Brings the stored verdicts of already-stamped rows back in line with the rule
     * that is in force now (#777).
     *
     * <p>
     * The stamping pass only ever selects rows that have not been evaluated, so a
     * row stamped under an older rule keeps that verdict for good. One deployment
     * was left with 4027 rows saying {@code PARTIAL} long after the rule had
     * stopped meaning that — and a reader querying the column cannot tell such a
     * row from a freshly stamped one, so the stale label is simply wrong data, not
     * a second calibre.
     * </p>
     *
     * <p>
     * Only the verdict moves. The frozen prices and the amount are facts about the
     * event; the verdict is derived from them, which is why recomputing it revises
     * no decision that ever moved money.
     * </p>
     *
     * @return how many rows carried a verdict the current rule disagrees with
     */
    private long reclassifyStampedRows(UUID tenantId, Instant from, Instant to) {
        long reclassified = 0;
        Instant cursorAt = Instant.EPOCH;
        UUID cursorId = new UUID(0L, 0L);
        while (true) {
            List<Map<String, Object>> batch = jdbc.query(RECLASSIFY_BATCH,
                    new MapSqlParameterSource("tenantId", tenantId).addValue("from", Timestamp.from(from))
                            .addValue("to", Timestamp.from(to)).addValue("limit", BATCH)
                            .addValue("cursorAt", Timestamp.from(cursorAt)).addValue("cursorId", cursorId),
                    UsagePriceBackfillService::stampedRow);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : batch) {
                String status = classify(row);
                if (!status.equals(row.get("storedStatus"))) {
                    reclassified += jdbc.update(RECLASSIFY_ROW, new MapSqlParameterSource("status", status)
                            .addValue("id", row.get("id")).addValue("tenantId", tenantId));
                }
                cursorAt = (Instant) row.get("occurredAt");
                cursorId = (UUID) row.get("id");
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        return reclassified;
    }

    /**
     * Fills in {@code base_cost_amount} on rows stamped before the column existed
     * (#771).
     *
     * <p>
     * Derives the amount from the prices the row <em>already froze</em> — no price
     * lookup, no status change — so it can only complete a record, never revise
     * one. Re-running is a no-op.
     * </p>
     *
     * <p>
     * Each pass either fills at least one row, which strictly shrinks the candidate
     * set, or it stops. That is what bounds the loop: a row that derived to NULL
     * would stay a candidate forever, so the selection is written to admit exactly
     * the rows {@link #baseCost} can price, and the "no progress" exit guards the
     * invariant if the two ever drift apart.
     * </p>
     *
     * @return how many rows were filled
     */
    private long fillBaseCost(UUID tenantId, Instant from, Instant to) {
        long filled = 0;
        while (true) {
            List<Map<String, Object>> batch = jdbc.query(SELECT_BASE_COST_BATCH, window(tenantId, from, to),
                    UsagePriceBackfillService::priceRow);
            if (batch.isEmpty()) {
                break;
            }
            long updated = 0;
            for (Map<String, Object> row : batch) {
                BigDecimal baseCost = baseCost(row);
                if (baseCost == null) {
                    continue;
                }
                updated += jdbc.update(FILL_BASE_COST, new MapSqlParameterSource("baseCost", baseCost)
                        .addValue("id", row.get("id")).addValue("tenantId", tenantId));
            }
            filled += updated;
            if (updated == 0) {
                break;
            }
        }
        return filled;
    }

    private static MapSqlParameterSource window(UUID tenantId, Instant from, Instant to) {
        return new MapSqlParameterSource("tenantId", tenantId).addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to)).addValue("limit", BATCH);
    }

    /**
     * The row shape both passes read, under names {@link #DIMENSIONS} already uses.
     *
     * <p>
     * A plain map, not {@code Map.of}: an unpriced dimension is legitimately null,
     * and {@code Map.of} rejects null values outright.
     * </p>
     */
    private static Map<String, Object> priceRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id", UUID.class));
        row.put("input", rs.getObject("price_input"));
        row.put("output", rs.getObject("price_output"));
        row.put("cacheRead", rs.getObject("price_cache_read"));
        row.put("cacheCreation", rs.getObject("price_cache_creation"));
        row.put("currency", rs.getString("price_currency"));
        row.put("source", rs.getString("price_source"));
        row.put("effectiveFrom", rs.getTimestamp("price_effective_from"));
        row.put("inputTokens", rs.getObject("input_tokens", Long.class));
        row.put("outputTokens", rs.getObject("output_tokens", Long.class));
        row.put("cacheReadTokens", rs.getObject("cache_read_input_tokens", Long.class));
        row.put("cacheCreationTokens", rs.getObject("cache_creation_input_tokens", Long.class));
        return row;
    }

    /**
     * {@link #priceRow} plus the two fields only the scan needs (#777): the verdict
     * already on the row, and the position the keyset cursor advances to.
     */
    private static Map<String, Object> stampedRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = priceRow(rs, rowNum);
        row.put("storedStatus", rs.getString("price_status"));
        row.put("occurredAt", rs.getTimestamp("occurred_at").toInstant());
        return row;
    }

    /**
     * All four dimensions priced → COMPLETE; some → PARTIAL; none → UNAVAILABLE.
     *
     * <p>
     * Note what this does <em>not</em> do: it never substitutes a zero price. A
     * dimension with no price row stays NULL no matter which status the row gets.
     * </p>
     */
    private static String classify(Map<String, Object> row) {
        int participating = 0;
        int priced = 0;
        for (Map.Entry<String, String> dimension : DIMENSIONS) {
            if (!carriesTokens(row, dimension.getValue())) {
                continue;
            }
            participating++;
            if (row.get(dimension.getKey()) != null) {
                priced++;
            }
        }
        // Nothing to price, or everything that took part was priced.
        if (participating == 0 || priced == participating) {
            return "COMPLETE";
        }
        return priced == 0 ? "UNAVAILABLE" : "PARTIAL";
    }

    private static boolean carriesTokens(Map<String, Object> row, String tokenKey) {
        Object tokens = row.get(tokenKey);
        return tokens instanceof Long value && value != 0L;
    }

    /**
     * The event's base cost: the sum of its <b>priced</b> dimensions, or NULL when
     * nothing could be priced.
     *
     * <p>
     * The NULL is load-bearing and is not zero. {@code UNAVAILABLE} means no price
     * was in force when this happened, which is a different fact from "the price
     * was 0" — and a stored 0 would later read as "free". The division goes through
     * the aggregator's {@code dividePerMillion} so the stored amount and the amount
     * a summary computes cannot drift apart.
     * </p>
     */
    private static BigDecimal baseCost(Map<String, Object> row) {
        BigDecimal undivided = BigDecimal.ZERO;
        boolean anyPriced = false;
        for (Map.Entry<String, String> dimension : DIMENSIONS) {
            Object price = row.get(dimension.getKey());
            if (price == null || !carriesTokens(row, dimension.getValue())) {
                continue;
            }
            anyPriced = true;
            undivided = undivided
                    .add(BigDecimal.valueOf((Long) row.get(dimension.getValue())).multiply((BigDecimal) price));
        }
        return anyPriced ? UsageStatsAggregator.dividePerMillion(undivided) : null;
    }

    private void stamp(UUID tenantId, Map<String, Object> row, String status) {
        Object effectiveFrom = row.get("effectiveFrom");
        jdbc.update(UPDATE_ROW,
                new MapSqlParameterSource("input", row.get("input")).addValue("output", row.get("output"))
                        .addValue("cacheRead", row.get("cacheRead")).addValue("cacheCreation", row.get("cacheCreation"))
                        .addValue("currency", row.get("currency")).addValue("source", row.get("source"))
                        .addValue("effectiveFrom", effectiveFrom == null ? null : (Timestamp) effectiveFrom)
                        .addValue("status", status).addValue("baseCost", baseCost(row)).addValue("id", row.get("id"))
                        .addValue("tenantId", tenantId));
    }

    private static void validateWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID",
                    "The period is invalid: from must be strictly before to");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_TOO_WIDE",
                    "The period is wider than the maximum of " + MAX_WINDOW.toDays() + " days");
        }
    }
}
