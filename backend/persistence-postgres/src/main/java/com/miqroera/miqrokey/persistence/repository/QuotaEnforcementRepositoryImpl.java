package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.QuotaEnforcement;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.QuotaEnforcementRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class QuotaEnforcementRepositoryImpl implements QuotaEnforcementRepository {

    private static final String COLS = "id, tenant_id, scope_type, scope_id, rule_id, metric, period, limit_value,"
            + " used_value, used_percent, window_from, window_to, created_at, updated_at";

    private static final RowMapper<QuotaEnforcement> ROW_MAPPER = (rs, rowNum) -> new QuotaEnforcement(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
            QuotaScopeType.valueOf(rs.getString("scope_type")), (UUID) rs.getObject("scope_id"),
            (UUID) rs.getObject("rule_id"), QuotaMetric.valueOf(rs.getString("metric")),
            QuotaPeriod.valueOf(rs.getString("period")), rs.getLong("limit_value"), rs.getBigDecimal("used_value"),
            rs.getBigDecimal("used_percent"), rs.getTimestamp("window_from").toInstant(),
            rs.getTimestamp("window_to").toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaEnforcementRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<QuotaEnforcement> findAllByTenant(UUID tenantId) {
        return jdbc.query("SELECT " + COLS + " FROM quota_enforcement WHERE tenant_id = :tenantId"
                + " ORDER BY scope_type, scope_id", new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public QuotaEnforcement upsert(QuotaEnforcement enforcement) {
        // created_at is deliberately not overwritten: it marks when the scope
        // entered the blocked state, which survives a window or rule rewrite.
        return jdbc.queryForObject("""
                INSERT INTO quota_enforcement (id, tenant_id, scope_type, scope_id, rule_id, metric, period,
                    limit_value, used_value, used_percent, window_from, window_to, created_at, updated_at)
                VALUES (:id, :tenantId, :scopeType, :scopeId, :ruleId, :metric, :period, :limitValue,
                    :usedValue, :usedPercent, :windowFrom, :windowTo, :createdAt, :updatedAt)
                ON CONFLICT (tenant_id, scope_type, scope_id) DO UPDATE SET
                    rule_id = EXCLUDED.rule_id,
                    metric = EXCLUDED.metric,
                    period = EXCLUDED.period,
                    limit_value = EXCLUDED.limit_value,
                    used_value = EXCLUDED.used_value,
                    used_percent = EXCLUDED.used_percent,
                    window_from = EXCLUDED.window_from,
                    window_to = EXCLUDED.window_to,
                    updated_at = EXCLUDED.updated_at
                RETURNING
                """ + COLS,
                new MapSqlParameterSource().addValue("id", enforcement.id())
                        .addValue("tenantId", enforcement.tenantId())
                        .addValue("scopeType", enforcement.scopeType().name())
                        .addValue("scopeId", enforcement.scopeId()).addValue("ruleId", enforcement.ruleId())
                        .addValue("metric", enforcement.metric().name()).addValue("period", enforcement.period().name())
                        .addValue("limitValue", enforcement.limitValue())
                        .addValue("usedValue", enforcement.usedValue())
                        .addValue("usedPercent", enforcement.usedPercent())
                        .addValue("windowFrom", Timestamp.from(enforcement.windowFrom()))
                        .addValue("windowTo", Timestamp.from(enforcement.windowTo()))
                        .addValue("createdAt", Timestamp.from(enforcement.createdAt()))
                        .addValue("updatedAt", Timestamp.from(enforcement.updatedAt())),
                ROW_MAPPER);
    }

    @Override
    @Transactional
    public boolean deleteByScope(UUID tenantId, QuotaScopeType scopeType, UUID scopeId) {
        return jdbc.update("DELETE FROM quota_enforcement"
                + " WHERE tenant_id = :tenantId AND scope_type = :scopeType AND scope_id = :scopeId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("scopeType", scopeType.name())
                        .addValue("scopeId", scopeId)) == 1;
    }
}
