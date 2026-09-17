package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.UsagePriceBackfillResult;
import com.miqroera.miqrokey.domain.service.AuditService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
                SELECT ue.id, ue.provider_product_id, ue.model_id, ue.occurred_at
                  FROM usage_event ue
                 WHERE ue.tenant_id = :tenantId
                   AND ue.occurred_at >= :from AND ue.occurred_at < :to
                   AND ue.price_status IS NULL
                 ORDER BY ue.occurred_at, ue.id
                 LIMIT :limit
            )
            SELECT t.id,
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

    private static final String UPDATE_ROW = """
            UPDATE usage_event
               SET price_input = :input, price_output = :output, price_cache_read = :cacheRead,
                   price_cache_creation = :cacheCreation, price_currency = :currency,
                   price_effective_from = :effectiveFrom, price_source = :source, price_status = :status
             WHERE id = :id AND tenant_id = :tenantId AND price_status IS NULL
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
            List<Map<String, Object>> batch = jdbc.query(SELECT_BATCH, new MapSqlParameterSource("tenantId", tenantId)
                    .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)).addValue("limit", BATCH),
                    (rs, rowNum) -> {
                        // A plain map, not Map.of: unpriced dimensions are legitimately null,
                        // and Map.of rejects null values outright.
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("id", rs.getObject("id", UUID.class));
                        row.put("input", rs.getObject("price_input"));
                        row.put("output", rs.getObject("price_output"));
                        row.put("cacheRead", rs.getObject("price_cache_read"));
                        row.put("cacheCreation", rs.getObject("price_cache_creation"));
                        row.put("currency", rs.getString("price_currency"));
                        row.put("source", rs.getString("price_source"));
                        row.put("effectiveFrom", rs.getTimestamp("price_effective_from"));
                        return row;
                    });
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

        UsagePriceBackfillResult result = new UsagePriceBackfillResult(scanned, complete, partial, unavailable);
        LOG.info("Usage price backfill {}..{}: scanned={} complete={} partial={} unavailable={}", from, to,
                result.scanned(), result.complete(), result.partial(), result.unavailable());
        auditService.record(tenantId, adminId, "USAGE_PRICE_BACKFILL", "USAGE_EVENT", null,
                "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"scanned\":" + result.scanned() + ",\"complete\":"
                        + result.complete() + ",\"partial\":" + result.partial() + ",\"unavailable\":"
                        + result.unavailable() + "}",
                requestId);
        return result;
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
        int priced = 0;
        for (String key : List.of("input", "output", "cacheRead", "cacheCreation")) {
            if (row.get(key) != null) {
                priced++;
            }
        }
        if (priced == 4) {
            return "COMPLETE";
        }
        return priced == 0 ? "UNAVAILABLE" : "PARTIAL";
    }

    private void stamp(UUID tenantId, Map<String, Object> row, String status) {
        Object effectiveFrom = row.get("effectiveFrom");
        jdbc.update(UPDATE_ROW,
                new MapSqlParameterSource("input", row.get("input")).addValue("output", row.get("output"))
                        .addValue("cacheRead", row.get("cacheRead")).addValue("cacheCreation", row.get("cacheCreation"))
                        .addValue("currency", row.get("currency")).addValue("source", row.get("source"))
                        .addValue("effectiveFrom", effectiveFrom == null ? null : (Timestamp) effectiveFrom)
                        .addValue("status", status).addValue("id", row.get("id")).addValue("tenantId", tenantId));
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
