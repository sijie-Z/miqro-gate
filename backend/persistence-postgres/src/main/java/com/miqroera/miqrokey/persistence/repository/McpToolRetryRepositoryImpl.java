package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import com.miqroera.miqrokey.domain.model.McpToolRetryPolicy;
import com.miqroera.miqrokey.domain.repository.McpToolRetryRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Row mapper + upsert for {@code mcp_tool_retry_policy} (V46, issue #360/I13).
 * Condition CSV round-trips the same way as the service-level policy (V30).
 */
@Repository
@Transactional(readOnly = true)
public class McpToolRetryRepositoryImpl implements McpToolRetryRepository {

    private static final String COLS = "retry_enabled, retry_max, retry_conditions, retry_idempotency_confirmed,"
            + " version";

    private final NamedParameterJdbcTemplate jdbc;

    public McpToolRetryRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<McpToolRetryPolicy> find(UUID tenantId, UUID mcpToolId) {
        return jdbc.query("""
                SELECT
                """ + COLS + """
                        FROM mcp_tool_retry_policy
                        WHERE tenant_id = :tenantId AND mcp_tool_id = :toolId
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("toolId", mcpToolId),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty());
    }

    @Override
    @Transactional
    public McpToolRetryPolicy upsert(UUID tenantId, UUID mcpToolId, McpToolRetryPolicy policy, UUID updatedBy) {
        return jdbc.queryForObject("""
                INSERT INTO mcp_tool_retry_policy (mcp_tool_id, tenant_id, retry_enabled, retry_max, retry_conditions,
                    retry_idempotency_confirmed, version, updated_by, created_at, updated_at)
                VALUES (:toolId, :tenantId, :retryEnabled, :retryMax, :retryConditions, :idempotencyConfirmed, 0,
                    :updatedBy, now(), now())
                ON CONFLICT (mcp_tool_id) DO UPDATE SET
                    retry_enabled = EXCLUDED.retry_enabled,
                    retry_max = EXCLUDED.retry_max,
                    retry_conditions = EXCLUDED.retry_conditions,
                    retry_idempotency_confirmed = EXCLUDED.retry_idempotency_confirmed,
                    version = mcp_tool_retry_policy.version + 1,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                RETURNING
                """ + COLS, new MapSqlParameterSource().addValue("toolId", mcpToolId).addValue("tenantId", tenantId)
                .addValue("retryEnabled", policy.retryEnabled()).addValue("retryMax", policy.retryMax())
                .addValue("retryConditions", csvOfNames(policy.retryConditions()))
                .addValue("idempotencyConfirmed", policy.idempotencyConfirmed()).addValue("updatedBy", updatedBy),
                (rs, rowNum) -> map(rs));
    }

    private static McpToolRetryPolicy map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Set<McpResiliencePolicy.RetryCondition> conditions = new LinkedHashSet<>();
        for (String part : csvParts(rs.getString("retry_conditions"))) {
            conditions.add(McpResiliencePolicy.RetryCondition.valueOf(part));
        }
        return new McpToolRetryPolicy(rs.getBoolean("retry_enabled"), rs.getInt("retry_max"), conditions,
                rs.getBoolean("retry_idempotency_confirmed"), rs.getLong("version"));
    }

    private static String csvOfNames(Set<McpResiliencePolicy.RetryCondition> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static List<String> csvParts(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
