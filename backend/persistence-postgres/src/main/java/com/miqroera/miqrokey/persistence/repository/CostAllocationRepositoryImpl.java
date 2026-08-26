package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.repository.CostAllocationRepository;
import com.miqroera.miqrokey.domain.usage.CostAllocation;
import com.miqroera.miqrokey.domain.usage.CostAllocationTargetType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation for {@code cost_allocations} (V10, G4.3). The upsert is
 * keyed on the unique (subscription, period, target, algorithm version) — the
 * same allocation run replaces its own rows instead of duplicating them.
 */
@Repository
@Transactional(readOnly = true)
public class CostAllocationRepositoryImpl implements CostAllocationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CostAllocationRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public CostAllocation upsert(CostAllocation allocation) {
        jdbc.update("""
                INSERT INTO cost_allocations
                    (id, tenant_id, subscription_id, period_start, period_end, target_type, target_id, fixed_cost,
                     usage_cost, weight_tokens, allocated_amount, currency, algorithm_version, generated_at,
                     created_at)
                VALUES (:id, :tenantId, :subscriptionId, :periodStart, :periodEnd, :targetType, :targetId,
                        :fixedCost, :usageCost, :weightTokens, :allocatedAmount, :currency, :algorithmVersion,
                        :generatedAt, :createdAt)
                ON CONFLICT (subscription_id, period_start, period_end, target_type, target_id, algorithm_version)
                DO UPDATE SET fixed_cost = EXCLUDED.fixed_cost, usage_cost = EXCLUDED.usage_cost,
                              weight_tokens = EXCLUDED.weight_tokens,
                              allocated_amount = EXCLUDED.allocated_amount, generated_at = EXCLUDED.generated_at
                """, params(allocation));
        return allocation;
    }

    @Override
    public List<CostAllocation> findByPeriod(UUID tenantId, UUID subscriptionId, Instant periodStart,
            Instant periodEnd) {
        return jdbc.query("""
                SELECT * FROM cost_allocations
                WHERE tenant_id = :tenantId AND subscription_id = :subscriptionId
                  AND period_start = :periodStart AND period_end = :periodEnd
                ORDER BY generated_at DESC
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("subscriptionId", subscriptionId)
                .addValue("periodStart", Timestamp.from(periodStart)).addValue("periodEnd", Timestamp.from(periodEnd)),
                ROW_MAPPER);
    }

    private static MapSqlParameterSource params(CostAllocation a) {
        return new MapSqlParameterSource("id", a.id()).addValue("tenantId", a.tenantId())
                .addValue("subscriptionId", a.subscriptionId()).addValue("periodStart", Timestamp.from(a.periodStart()))
                .addValue("periodEnd", Timestamp.from(a.periodEnd())).addValue("targetType", a.targetType().name())
                .addValue("targetId", a.targetId()).addValue("fixedCost", a.fixedCost())
                .addValue("usageCost", a.usageCost()).addValue("weightTokens", a.weightTokens())
                .addValue("allocatedAmount", a.allocatedAmount()).addValue("currency", a.currency())
                .addValue("algorithmVersion", a.algorithmVersion())
                .addValue("generatedAt", Timestamp.from(a.generatedAt()))
                .addValue("createdAt", Timestamp.from(a.createdAt()));
    }

    private static final RowMapper<CostAllocation> ROW_MAPPER = (rs, rowNum) -> new CostAllocation(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("subscription_id"),
            rs.getTimestamp("period_start").toInstant(), rs.getTimestamp("period_end").toInstant(),
            CostAllocationTargetType.valueOf(rs.getString("target_type")), (UUID) rs.getObject("target_id"),
            rs.getObject("fixed_cost", BigDecimal.class), rs.getObject("usage_cost", BigDecimal.class),
            rs.getLong("weight_tokens"), rs.getObject("allocated_amount", BigDecimal.class), rs.getString("currency"),
            rs.getString("algorithm_version"), rs.getTimestamp("generated_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());
}
