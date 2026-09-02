package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.QuotaDefaultTemplate;
import com.miqroera.miqrokey.domain.model.QuotaMetric;
import com.miqroera.miqrokey.domain.model.QuotaPeriod;
import com.miqroera.miqrokey.domain.repository.QuotaDefaultTemplateRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class QuotaDefaultTemplateRepositoryImpl implements QuotaDefaultTemplateRepository {

    private static final String COLS = "tenant_id, enabled, metric, period, limit_value, updated_by, version,"
            + " created_at, updated_at";

    private static final RowMapper<QuotaDefaultTemplate> ROW_MAPPER = (rs, rowNum) -> new QuotaDefaultTemplate(
            (UUID) rs.getObject("tenant_id"), rs.getBoolean("enabled"), QuotaMetric.valueOf(rs.getString("metric")),
            QuotaPeriod.valueOf(rs.getString("period")), rs.getLong("limit_value"), (UUID) rs.getObject("updated_by"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaDefaultTemplateRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<QuotaDefaultTemplate> find(UUID tenantId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT " + COLS + " FROM quota_default_template WHERE tenant_id = :tenantId",
                            new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public QuotaDefaultTemplate upsertDefinition(QuotaDefaultTemplate template) {
        return jdbc.queryForObject("""
                INSERT INTO quota_default_template (tenant_id, enabled, metric, period, limit_value, updated_by,
                    version, created_at, updated_at)
                VALUES (:tenantId, :enabled, :metric, :period, :limitValue, :updatedBy, 0, :createdAt, :updatedAt)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    metric = EXCLUDED.metric,
                    period = EXCLUDED.period,
                    limit_value = EXCLUDED.limit_value,
                    enabled = EXCLUDED.enabled,
                    updated_by = EXCLUDED.updated_by,
                    version = quota_default_template.version + 1,
                    updated_at = EXCLUDED.updated_at
                RETURNING
                """ + COLS, new MapSqlParameterSource().addValue("tenantId", template.tenantId())
                .addValue("enabled", template.enabled()).addValue("metric", template.metric().name())
                .addValue("period", template.period().name()).addValue("limitValue", template.limitValue())
                .addValue("updatedBy", template.updatedBy()).addValue("createdAt", Timestamp.from(template.createdAt()))
                .addValue("updatedAt", Timestamp.from(template.updatedAt())), ROW_MAPPER);
    }

    @Override
    @Transactional
    public QuotaDefaultTemplate setEnabled(UUID tenantId, boolean enabled, UUID updatedBy) {
        return jdbc.queryForObject("""
                UPDATE quota_default_template
                SET enabled = :enabled, updated_by = :updatedBy, version = version + 1, updated_at = now()
                WHERE tenant_id = :tenantId
                RETURNING
                """ + COLS, new MapSqlParameterSource("tenantId", tenantId).addValue("enabled", enabled)
                .addValue("updatedBy", updatedBy), ROW_MAPPER);
    }
}
