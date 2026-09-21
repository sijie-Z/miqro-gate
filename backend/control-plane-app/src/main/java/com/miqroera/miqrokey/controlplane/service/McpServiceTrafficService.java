package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
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
 * Passive health view (#397): real-traffic outcome aggregates for one MCP
 * service, the complement to the active probe ({@code McpHealthChecker}). A
 * service can probe HEALTHY while real calls fail (upstream credential/quota/
 * 5xx) — this read surface makes that gap visible. Classification mirrors the
 * consumer activity overview (#338): {@code failed} = UPSTREAM_FAILURE +
 * CIRCUIT_OPEN; denial classes stay separate and never count as health
 * failures. Read-only pure metadata ({@code mcp_access_log}, V29); nothing here
 * blocks traffic or changes any gateway behaviour.
 */
@Service
public class McpServiceTrafficService {

    static final int MAX_WINDOW_HOURS = 168;

    private final NamedParameterJdbcTemplate jdbc;
    private final McpServiceRepository serviceRepository;

    public McpServiceTrafficService(NamedParameterJdbcTemplate jdbc, McpServiceRepository serviceRepository) {
        this.jdbc = jdbc;
        this.serviceRepository = serviceRepository;
    }

    public Map<String, Object> traffic(UUID tenantId, UUID serviceId, int hours) {
        if (hours < 1 || hours > MAX_WINDOW_HOURS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID",
                    "hours 必须在 1.." + MAX_WINDOW_HOURS + " 之间。");
        }
        McpService service = serviceRepository.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_SERVICE_NOT_FOUND", "MCP 服务不存在。"));
        Instant from = Instant.now().minusSeconds(hours * 3600L);
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId).addValue("serviceId", serviceId)
                .addValue("from", java.sql.Timestamp.from(from));

        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'FORWARDED') AS forwarded,
                       count(*) FILTER (WHERE status IN ('SERVICE_DENIED', 'TOOL_DENIED', 'TOOL_UNAVAILABLE',
                           'INVALID_ENVELOPE')) AS denied,
                       count(*) FILTER (WHERE status IN ('UPSTREAM_FAILURE', 'CIRCUIT_OPEN')) AS failed,
                       max(occurred_at) AS last_call,
                       max(occurred_at) FILTER (WHERE status IN ('UPSTREAM_FAILURE', 'CIRCUIT_OPEN')) AS last_failure
                FROM mcp_access_log
                WHERE tenant_id = :tenantId AND service_id = :serviceId AND occurred_at >= :from
                """, params);

        List<Map<String, Object>> topFailingTools = jdbc.query("""
                SELECT tool_name AS name, count(*) AS failures
                FROM mcp_access_log
                WHERE tenant_id = :tenantId AND service_id = :serviceId AND occurred_at >= :from
                  AND status IN ('UPSTREAM_FAILURE', 'CIRCUIT_OPEN') AND tool_name IS NOT NULL
                GROUP BY tool_name ORDER BY failures DESC, name LIMIT 5
                """, params,
                (rs, n) -> Map.<String, Object>of("name", rs.getString("name"), "failures", rs.getLong("failures")));

        long forwarded = ((Number) totals.get("forwarded")).longValue();
        long failed = ((Number) totals.get("failed")).longValue();
        long healthRelevant = forwarded + failed;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("serviceId", serviceId.toString());
        view.put("serviceName", service.name());
        view.put("windowHours", hours);
        view.put("totalCalls", ((Number) totals.get("total")).longValue());
        view.put("forwarded", forwarded);
        view.put("denied", ((Number) totals.get("denied")).longValue());
        view.put("failed", failed);
        view.put("failureRate", healthRelevant == 0 ? null : (double) failed / healthRelevant);
        Object last = totals.get("last_call");
        view.put("lastCallAt", last == null ? null : ((java.sql.Timestamp) last).toInstant().toString());
        Object lastFailure = totals.get("last_failure");
        view.put("lastFailureAt",
                lastFailure == null ? null : ((java.sql.Timestamp) lastFailure).toInstant().toString());
        view.put("topFailingTools", topFailingTools);
        return view;
    }
}
