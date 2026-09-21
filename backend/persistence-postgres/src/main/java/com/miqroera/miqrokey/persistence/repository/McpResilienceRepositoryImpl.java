package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.McpResiliencePolicy;
import com.miqroera.miqrokey.domain.repository.McpResilienceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Row mapper + upsert for {@code mcp_resilience_policy} (V30). Condition and
 * status-code columns are CSV text; the mapper mirrors the route-snapshot
 * loader's parse (both must stay in lockstep).
 */
@Repository
@Transactional
public class McpResilienceRepositoryImpl implements McpResilienceRepository {

    private static final String COLS = "retry_enabled, retry_max, retry_conditions, retry_idempotency_confirmed,"
            + " breaker_enabled, breaker_window_seconds, breaker_min_requests, breaker_error_enabled,"
            + " breaker_error_ratio, breaker_error_status_codes, breaker_slow_enabled, breaker_slow_call_ms,"
            + " breaker_slow_ratio, breaker_open_seconds, breaker_probe_count, breaker_probe_success,"
            + " breaker_skip_retry, version";

    private final NamedParameterJdbcTemplate jdbc;

    public McpResilienceRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<McpResiliencePolicy> find(UUID tenantId, UUID mcpServiceId) {
        return jdbc.query("""
                SELECT
                """ + COLS + """
                        FROM mcp_resilience_policy
                        WHERE tenant_id = :tenantId AND mcp_service_id = :serviceId
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("serviceId", mcpServiceId),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty());
    }

    @Override
    public McpResiliencePolicy upsert(UUID tenantId, UUID mcpServiceId, McpResiliencePolicy policy, UUID updatedBy) {
        return jdbc.queryForObject("""
                INSERT INTO mcp_resilience_policy (mcp_service_id, tenant_id, retry_enabled, retry_max,
                    retry_conditions, retry_idempotency_confirmed, breaker_enabled, breaker_window_seconds,
                    breaker_min_requests, breaker_error_enabled, breaker_error_ratio, breaker_error_status_codes,
                    breaker_slow_enabled, breaker_slow_call_ms, breaker_slow_ratio, breaker_open_seconds,
                    breaker_probe_count, breaker_probe_success, breaker_skip_retry, version, created_by, updated_by,
                    created_at, updated_at)
                VALUES (:serviceId, :tenantId, :retryEnabled, :retryMax, :retryConditions,
                    :idempotencyConfirmed, :breakerEnabled, :windowSeconds, :minRequests, :errorEnabled, :errorRatio,
                    :errorStatusCodes, :slowEnabled, :slowCallMs, :slowRatio, :openSeconds, :probeCount,
                    :probeSuccess, :skipRetry, 0, :updatedBy, :updatedBy, now(), now())
                ON CONFLICT (mcp_service_id) DO UPDATE SET
                    retry_enabled = EXCLUDED.retry_enabled,
                    retry_max = EXCLUDED.retry_max,
                    retry_conditions = EXCLUDED.retry_conditions,
                    retry_idempotency_confirmed = EXCLUDED.retry_idempotency_confirmed,
                    breaker_enabled = EXCLUDED.breaker_enabled,
                    breaker_window_seconds = EXCLUDED.breaker_window_seconds,
                    breaker_min_requests = EXCLUDED.breaker_min_requests,
                    breaker_error_enabled = EXCLUDED.breaker_error_enabled,
                    breaker_error_ratio = EXCLUDED.breaker_error_ratio,
                    breaker_error_status_codes = EXCLUDED.breaker_error_status_codes,
                    breaker_slow_enabled = EXCLUDED.breaker_slow_enabled,
                    breaker_slow_call_ms = EXCLUDED.breaker_slow_call_ms,
                    breaker_slow_ratio = EXCLUDED.breaker_slow_ratio,
                    breaker_open_seconds = EXCLUDED.breaker_open_seconds,
                    breaker_probe_count = EXCLUDED.breaker_probe_count,
                    breaker_probe_success = EXCLUDED.breaker_probe_success,
                    breaker_skip_retry = EXCLUDED.breaker_skip_retry,
                    version = mcp_resilience_policy.version + 1,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                RETURNING
                """ + COLS, new MapSqlParameterSource().addValue("serviceId", mcpServiceId)
                .addValue("tenantId", tenantId).addValue("retryEnabled", policy.retryEnabled())
                .addValue("retryMax", policy.retryMax())
                .addValue("retryConditions", csvOfNames(policy.retryConditions()))
                .addValue("idempotencyConfirmed", policy.idempotencyConfirmed())
                .addValue("breakerEnabled", policy.breakerEnabled())
                .addValue("windowSeconds", policy.breakerWindowSeconds())
                .addValue("minRequests", policy.breakerMinRequests())
                .addValue("errorEnabled", policy.breakerErrorEnabled())
                .addValue("errorRatio", policy.breakerErrorRatio())
                .addValue("errorStatusCodes", csvInt(policy.breakerErrorStatusCodes()))
                .addValue("slowEnabled", policy.breakerSlowEnabled()).addValue("slowCallMs", policy.breakerSlowCallMs())
                .addValue("slowRatio", policy.breakerSlowRatio()).addValue("openSeconds", policy.breakerOpenSeconds())
                .addValue("probeCount", policy.breakerProbeCount())
                .addValue("probeSuccess", policy.breakerProbeSuccess()).addValue("skipRetry", policy.breakerSkipRetry())
                .addValue("updatedBy", updatedBy), (rs, rowNum) -> map(rs));
    }

    private static McpResiliencePolicy map(ResultSet rs) throws SQLException {
        Set<McpResiliencePolicy.RetryCondition> conditions = new LinkedHashSet<>();
        for (String part : csvParts(rs.getString("retry_conditions"))) {
            conditions.add(McpResiliencePolicy.RetryCondition.valueOf(part));
        }
        Set<Integer> codes = new LinkedHashSet<>();
        for (String part : csvParts(rs.getString("breaker_error_status_codes"))) {
            codes.add(Integer.valueOf(part));
        }
        return new McpResiliencePolicy(rs.getBoolean("retry_enabled"), rs.getInt("retry_max"), conditions,
                rs.getBoolean("retry_idempotency_confirmed"), rs.getBoolean("breaker_enabled"),
                rs.getInt("breaker_window_seconds"), rs.getInt("breaker_min_requests"),
                rs.getBoolean("breaker_error_enabled"), rs.getInt("breaker_error_ratio"), codes,
                rs.getBoolean("breaker_slow_enabled"), rs.getInt("breaker_slow_call_ms"),
                rs.getInt("breaker_slow_ratio"), rs.getInt("breaker_open_seconds"), rs.getInt("breaker_probe_count"),
                rs.getInt("breaker_probe_success"), rs.getBoolean("breaker_skip_retry"), rs.getLong("version"));
    }

    private static String csvOfNames(Set<McpResiliencePolicy.RetryCondition> values) {
        return String.join(",", values.stream().map(Enum::name).toList());
    }

    private static String csvInt(Set<Integer> values) {
        return String.join(",", values.stream().map(String::valueOf).toList());
    }

    private static java.util.List<String> csvParts(String value) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (value == null || value.isBlank()) {
            return parts;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }
}
