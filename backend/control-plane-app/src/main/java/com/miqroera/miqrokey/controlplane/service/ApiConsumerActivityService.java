package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-consumer MCP call overview (issue #338, coverage-matrix I5): aggregates
 * the pure-metadata {@code mcp_access_log} (F15/V29) into a window summary —
 * totals by outcome class, top tools and services, last call. Read-only; no new
 * metrics labels and no content is touched. Requests without a trustworthy
 * identity (401/404 pre-resolution) are not logged by design and therefore not
 * counted.
 */
@Service
public class ApiConsumerActivityService {

    static final int MAX_WINDOW_HOURS = 168;

    private final NamedParameterJdbcTemplate jdbc;
    private final ApiConsumerRepository consumerRepository;

    public ApiConsumerActivityService(NamedParameterJdbcTemplate jdbc, ApiConsumerRepository consumerRepository) {
        this.jdbc = jdbc;
        this.consumerRepository = consumerRepository;
    }

    public Map<String, Object> activity(UUID tenantId, UUID consumerId, int hours) {
        if (hours < 1 || hours > MAX_WINDOW_HOURS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID",
                    "hours 必须在 1.." + MAX_WINDOW_HOURS + " 之间。");
        }
        consumerRepository.findByIdAndTenantId(consumerId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONSUMER_NOT_FOUND", "消费者不存在。"));
        Instant from = Instant.now().minusSeconds(hours * 3600L);
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("consumerId", consumerId).addValue("from", java.sql.Timestamp.from(from));

        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'FORWARDED') AS forwarded,
                       count(*) FILTER (WHERE status IN ('SERVICE_DENIED', 'TOOL_DENIED', 'TOOL_UNAVAILABLE',
                           'INVALID_ENVELOPE')) AS denied,
                       count(*) FILTER (WHERE status IN ('UPSTREAM_FAILURE', 'CIRCUIT_OPEN')) AS failed,
                       max(occurred_at) AS last_call
                FROM mcp_access_log
                WHERE tenant_id = :tenantId AND consumer_id = :consumerId AND occurred_at >= :from
                """, params);

        List<Map<String, Object>> topTools = jdbc.query("""
                SELECT tool_name AS name, count(*) AS calls
                FROM mcp_access_log
                WHERE tenant_id = :tenantId AND consumer_id = :consumerId AND occurred_at >= :from
                  AND tool_name IS NOT NULL
                GROUP BY tool_name ORDER BY calls DESC, name LIMIT 5
                """, params,
                (rs, n) -> Map.<String, Object>of("name", rs.getString("name"), "calls", rs.getLong("calls")));

        List<Map<String, Object>> topServices = jdbc.query("""
                SELECT service_name AS name, count(*) AS calls
                FROM mcp_access_log
                WHERE tenant_id = :tenantId AND consumer_id = :consumerId AND occurred_at >= :from
                GROUP BY service_name ORDER BY calls DESC, name LIMIT 5
                """, params,
                (rs, n) -> Map.<String, Object>of("name", rs.getString("name"), "calls", rs.getLong("calls")));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("consumerId", consumerId.toString());
        view.put("windowHours", hours);
        view.put("totalCalls", ((Number) totals.get("total")).longValue());
        view.put("forwarded", ((Number) totals.get("forwarded")).longValue());
        view.put("denied", ((Number) totals.get("denied")).longValue());
        view.put("failed", ((Number) totals.get("failed")).longValue());
        Object last = totals.get("last_call");
        view.put("lastCallAt", last == null ? null : ((java.sql.Timestamp) last).toInstant().toString());
        view.put("topTools", topTools);
        view.put("topServices", topServices);
        return view;
    }
}
