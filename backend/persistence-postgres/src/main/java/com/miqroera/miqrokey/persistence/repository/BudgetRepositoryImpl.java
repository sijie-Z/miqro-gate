package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Budget;
import com.miqroera.miqrokey.domain.repository.BudgetRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class BudgetRepositoryImpl implements BudgetRepository {

    private static final RowMapper<Budget> ROW_MAPPER = (rs, rowNum) -> new Budget((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("project_id"), rs.getString("period_month"),
            rs.getObject("amount", BigDecimal.class), rs.getString("currency"),
            rs.getObject("alert_threshold_pct", BigDecimal.class), rs.getString("status"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public BudgetRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Budget upsert(Budget budget) {
        jdbc.update("""
                INSERT INTO budget
                    (id, tenant_id, project_id, period_month, amount, currency, alert_threshold_pct, status, version,
                     created_at, updated_at)
                VALUES (:id, :tenantId, :projectId, :month, :amount, :currency, :threshold, :status, 0, now(), now())
                ON CONFLICT (tenant_id, project_id, period_month) DO UPDATE
                    SET amount = EXCLUDED.amount, currency = EXCLUDED.currency,
                        alert_threshold_pct = EXCLUDED.alert_threshold_pct, status = EXCLUDED.status,
                        version = budget.version + 1, updated_at = now()
                """, params(budget));
        return findByProjectAndMonth(budget.tenantId(), budget.projectId(), budget.periodMonth()).orElse(budget);
    }

    @Override
    public Optional<Budget> findByProjectAndMonth(UUID tenantId, UUID projectId, String month) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM budget WHERE tenant_id = :tenantId AND project_id = :projectId"
                            + " AND period_month = :month",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("projectId", projectId).addValue("month",
                            month),
                    ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Budget> findAllByTenantAndMonth(UUID tenantId, String month) {
        return jdbc.query(
                "SELECT * FROM budget WHERE tenant_id = :tenantId AND period_month = :month ORDER BY project_id",
                new MapSqlParameterSource("tenantId", tenantId).addValue("month", month), ROW_MAPPER);
    }

    @Override
    @Transactional
    public boolean delete(UUID tenantId, UUID projectId, String month) {
        return jdbc.update("""
                DELETE FROM budget WHERE tenant_id = :tenantId AND project_id = :projectId AND period_month = :month
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("projectId", projectId).addValue("month",
                month)) > 0;
    }

    private static MapSqlParameterSource params(Budget b) {
        return new MapSqlParameterSource("id", b.id()).addValue("tenantId", b.tenantId())
                .addValue("projectId", b.projectId()).addValue("month", b.periodMonth()).addValue("amount", b.amount())
                .addValue("currency", b.currency()).addValue("threshold", b.alertThresholdPct())
                .addValue("status", b.status());
    }
}
