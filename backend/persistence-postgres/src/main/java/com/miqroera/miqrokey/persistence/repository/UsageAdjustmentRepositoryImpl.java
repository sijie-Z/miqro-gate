package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.repository.UsageAdjustmentRepository;
import com.miqroera.miqrokey.domain.usage.AdjustmentTarget;
import com.miqroera.miqrokey.domain.usage.AdjustmentType;
import com.miqroera.miqrokey.domain.usage.CacheLevel;
import com.miqroera.miqrokey.domain.usage.UsageAdjustment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only JDBC implementation of {@link UsageAdjustmentRepository} (#709).
 *
 * <p>
 * The ledger has no UPDATE and no DELETE path — undoing a correction appends a
 * reversal row — so this class only ever inserts and reads.
 * </p>
 */
@Repository
@Transactional(readOnly = true)
public class UsageAdjustmentRepositoryImpl implements UsageAdjustmentRepository {

    private static final String COLUMNS = """
            id, tenant_id, usage_event_id, adjustment_type,
            input_tokens_delta, output_tokens_delta, cache_read_tokens_delta, cache_creation_tokens_delta,
            amount_delta, currency_code, reason, reason_code,
            reconciliation_row_id, reversal_of_id, created_by, created_at, idempotency_key
            """;

    private static final String INSERT_ADJUSTMENT = """
            INSERT INTO usage_adjustments (
                id, tenant_id, usage_event_id, adjustment_type,
                input_tokens_delta, output_tokens_delta, cache_read_tokens_delta, cache_creation_tokens_delta,
                amount_delta, currency_code, reason, reason_code,
                reconciliation_row_id, reversal_of_id, created_by, created_at, idempotency_key)
            VALUES (
                :id, :tenantId, :usageEventId, :adjustmentType,
                :inputDelta, :outputDelta, :cacheReadDelta, :cacheCreationDelta,
                :amountDelta, :currencyCode, :reason, :reasonCode,
                :reconciliationRowId, :reversalOfId, :createdBy, :createdAt, :idempotencyKey)
            ON CONFLICT (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
            """;

    /**
     * Validation read for a pending adjustment: the observed counts (normalized the
     * same way the admin usage list and the export do, so a row can never present
     * two different input counts) plus the deltas already booked against it.
     *
     * <p>
     * The deltas are aggregated in a subquery rather than joined row-by-row: one
     * usage event can accumulate many adjustments, and a plain LEFT JOIN would
     * multiply the observed counts by that fan-out.
     * </p>
     */
    private static final String SELECT_ADJUSTMENT_TARGET = """
            SELECT e.id AS usage_event_id, e.tenant_id, e.cache_level,
                   COALESCE(e.input_tokens, e.prompt_tokens) AS input_tokens,
                   COALESCE(e.output_tokens, e.completion_tokens) AS output_tokens,
                   e.cache_read_input_tokens, e.cache_creation_input_tokens,
                   COALESCE(a.input_delta, 0) AS input_delta,
                   COALESCE(a.output_delta, 0) AS output_delta,
                   COALESCE(a.cache_read_delta, 0) AS cache_read_delta,
                   COALESCE(a.cache_creation_delta, 0) AS cache_creation_delta
              FROM usage_event e
              LEFT JOIN (
                   SELECT usage_event_id,
                          SUM(input_tokens_delta) AS input_delta,
                          SUM(output_tokens_delta) AS output_delta,
                          SUM(cache_read_tokens_delta) AS cache_read_delta,
                          SUM(cache_creation_tokens_delta) AS cache_creation_delta
                     FROM usage_adjustments
                    WHERE tenant_id = :tenantId
                    GROUP BY usage_event_id
              ) a ON a.usage_event_id = e.id
             WHERE e.tenant_id = :tenantId
               AND e.id = :usageEventId
            """;

    private static final RowMapper<UsageAdjustment> MAPPER = (rs, rowNum) -> new UsageAdjustment(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("usage_event_id", UUID.class), AdjustmentType.valueOf(rs.getString("adjustment_type")),
            toLong(rs, "input_tokens_delta"), toLong(rs, "output_tokens_delta"), toLong(rs, "cache_read_tokens_delta"),
            toLong(rs, "cache_creation_tokens_delta"), rs.getBigDecimal("amount_delta"), rs.getString("currency_code"),
            rs.getString("reason"), rs.getString("reason_code"), rs.getObject("reconciliation_row_id", UUID.class),
            rs.getObject("reversal_of_id", UUID.class), rs.getObject("created_by", UUID.class),
            toInstant(rs.getTimestamp("created_at")), rs.getString("idempotency_key"));

    private final NamedParameterJdbcTemplate jdbc;

    public UsageAdjustmentRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UsageAdjustment append(UsageAdjustment adjustment) {
        int inserted = jdbc.update(INSERT_ADJUSTMENT, params(adjustment));
        if (inserted > 0 || adjustment.idempotencyKey() == null) {
            return adjustment;
        }
        // The key was already used: return the row that won, so a retried
        // submission is indistinguishable from the original.
        return findByIdempotencyKey(adjustment.tenantId(), adjustment.idempotencyKey()).orElse(adjustment);
    }

    @Override
    public Optional<UsageAdjustment> findById(UUID tenantId, UUID adjustmentId) {
        if (tenantId == null || adjustmentId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM usage_adjustments WHERE tenant_id = :tenantId AND id = :id",
                Map.of("tenantId", tenantId, "id", adjustmentId), MAPPER).stream().findFirst();
    }

    @Override
    public Optional<UsageAdjustment> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        if (tenantId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM usage_adjustments WHERE tenant_id = :tenantId AND idempotency_key = :key",
                Map.of("tenantId", tenantId, "key", idempotencyKey), MAPPER).stream().findFirst();
    }

    @Override
    public List<UsageAdjustment> findByUsageEventId(UUID tenantId, UUID usageEventId) {
        if (tenantId == null || usageEventId == null) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM usage_adjustments
                 WHERE tenant_id = :tenantId AND usage_event_id = :usageEventId
                 ORDER BY created_at, id
                """, Map.of("tenantId", tenantId, "usageEventId", usageEventId), MAPPER);
    }

    @Override
    public Optional<UUID> findUsageEventIdByGatewayRequestId(UUID tenantId, String gatewayRequestId) {
        if (tenantId == null || gatewayRequestId == null || gatewayRequestId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT id FROM usage_event
                 WHERE tenant_id = :tenantId AND gateway_request_id = :gatewayRequestId
                 ORDER BY (cache_level = 'UPSTREAM') DESC, occurred_at DESC, id
                 LIMIT 1
                """, Map.of("tenantId", tenantId, "gatewayRequestId", gatewayRequestId),
                (rs, rowNum) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    @Override
    public Optional<AdjustmentTarget> findAdjustmentTarget(UUID tenantId, UUID usageEventId) {
        if (tenantId == null || usageEventId == null) {
            return Optional.empty();
        }
        return jdbc.query(SELECT_ADJUSTMENT_TARGET, Map.of("tenantId", tenantId, "usageEventId", usageEventId),
                (rs, rowNum) -> new AdjustmentTarget(rs.getObject("usage_event_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), CacheLevel.valueOf(rs.getString("cache_level")),
                        toLong(rs, "input_tokens"), toLong(rs, "output_tokens"), toLong(rs, "cache_read_input_tokens"),
                        toLong(rs, "cache_creation_input_tokens"), rs.getLong("input_delta"),
                        rs.getLong("output_delta"), rs.getLong("cache_read_delta"), rs.getLong("cache_creation_delta")))
                .stream().findFirst();
    }

    @Override
    @Transactional
    public void lockUsageEvent(UUID usageEventId) {
        // A transaction-scoped advisory lock, the same mechanism the audit chain uses.
        // It is correct even when the event row does not exist, unlike SELECT ... FOR
        // UPDATE, which cannot lock a non-existent row. Unrelated events colliding on
        // the same key would only serialise unnecessarily, never corrupt.
        jdbc.getJdbcTemplate().query("SELECT pg_advisory_xact_lock(?)", rs -> {
        }, lockKey(usageEventId));
    }

    static long lockKey(UUID usageEventId) {
        return usageEventId.getMostSignificantBits() ^ usageEventId.getLeastSignificantBits();
    }

    private static MapSqlParameterSource params(UsageAdjustment a) {
        // MapSqlParameterSource rather than Map.of: most columns are legitimately null.
        return new MapSqlParameterSource().addValue("id", a.id()).addValue("tenantId", a.tenantId())
                .addValue("usageEventId", a.usageEventId()).addValue("adjustmentType", a.adjustmentType().name())
                .addValue("inputDelta", a.inputTokensDelta()).addValue("outputDelta", a.outputTokensDelta())
                .addValue("cacheReadDelta", a.cacheReadTokensDelta())
                .addValue("cacheCreationDelta", a.cacheCreationTokensDelta()).addValue("amountDelta", a.amountDelta())
                .addValue("currencyCode", a.currencyCode()).addValue("reason", a.reason())
                .addValue("reasonCode", a.reasonCode()).addValue("reconciliationRowId", a.reconciliationRowId())
                .addValue("reversalOfId", a.reversalOfId()).addValue("createdBy", a.createdBy())
                .addValue("createdAt", Timestamp.from(a.createdAt())).addValue("idempotencyKey", a.idempotencyKey());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Long toLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
