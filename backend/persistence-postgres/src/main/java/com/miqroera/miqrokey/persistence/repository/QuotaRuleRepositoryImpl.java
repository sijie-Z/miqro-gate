package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.model.QuotaRule;
import com.miqroera.miqrokey.domain.model.QuotaRuleStatus;
import com.miqroera.miqrokey.domain.model.QuotaScopeType;
import com.miqroera.miqrokey.domain.repository.QuotaRuleRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class QuotaRuleRepositoryImpl implements QuotaRuleRepository {

    private static final String COLS = "id, tenant_id, scope_type, scope_id, metric, period, limit_value,"
            + " warn_percent, status, created_by, version, created_at, updated_at";

    private static final RowMapper<QuotaRule> ROW_MAPPER = (rs, rowNum) -> new QuotaRule((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), QuotaScopeType.valueOf(rs.getString("scope_type")),
            (UUID) rs.getObject("scope_id"), QuotaMetric.valueOf(rs.getString("metric")),
            QuotaPeriod.valueOf(rs.getString("period")), rs.getLong("limit_value"), rs.getInt("warn_percent"),
            QuotaRuleStatus.valueOf(rs.getString("status")), (UUID) rs.getObject("created_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaRuleRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public QuotaRule upsert(QuotaRule rule) {
        return jdbc.queryForObject("""
                INSERT INTO quota_rules (id, tenant_id, scope_type, scope_id, metric, period, limit_value,
                    warn_percent, status, created_by, version, created_at, updated_at)
                VALUES (:id, :tenantId, :scopeType, :scopeId, :metric, :period, :limitValue,
                    :warnPercent, :status, :createdBy, 0, :createdAt, :updatedAt)
                ON CONFLICT (tenant_id, scope_type, scope_id, metric, period) DO UPDATE SET
                    limit_value = EXCLUDED.limit_value,
                    warn_percent = EXCLUDED.warn_percent,
                    status = EXCLUDED.status,
                    version = quota_rules.version + 1,
                    updated_at = EXCLUDED.updated_at
                RETURNING
                """ + COLS,
                new MapSqlParameterSource().addValue("id", rule.id()).addValue("tenantId", rule.tenantId())
                        .addValue("scopeType", rule.scopeType().name()).addValue("scopeId", rule.scopeId())
                        .addValue("metric", rule.metric().name()).addValue("period", rule.period().name())
                        .addValue("limitValue", rule.limitValue()).addValue("warnPercent", rule.warnPercent())
                        .addValue("status", rule.status().name()).addValue("createdBy", rule.createdBy())
                        .addValue("createdAt", Timestamp.from(rule.createdAt()))
                        .addValue("updatedAt", Timestamp.from(rule.updatedAt())),
                ROW_MAPPER);
    }

    @Override
    public Optional<QuotaRule> findById(UUID tenantId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLS + " FROM quota_rules" + " WHERE tenant_id = :tenantId AND id = :id",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<QuotaRule> findByKey(UUID tenantId, QuotaScopeType scopeType, UUID scopeId, QuotaMetric metric,
            QuotaPeriod period) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLS + " FROM quota_rules"
                            + " WHERE tenant_id = :tenantId AND scope_type = :scopeType AND scope_id = :scopeId"
                            + " AND metric = :metric AND period = :period",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("scopeType", scopeType.name())
                            .addValue("scopeId", scopeId).addValue("metric", metric.name())
                            .addValue("period", period.name()),
                    ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<QuotaRule> findAllByTenant(UUID tenantId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM quota_rules WHERE tenant_id = :tenantId"
                        + " ORDER BY scope_type, scope_id, metric, period",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public boolean delete(UUID tenantId, UUID id) {
        return jdbc.update("DELETE FROM quota_rules WHERE tenant_id = :tenantId AND id = :id",
                new MapSqlParameterSource("tenantId", tenantId).addValue("id", id)) == 1;
    }
}
