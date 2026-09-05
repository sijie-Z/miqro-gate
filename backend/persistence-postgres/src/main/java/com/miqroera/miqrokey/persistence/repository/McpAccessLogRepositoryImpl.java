package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
import com.miqroera.miqrokey.domain.repository.McpAccessLogRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read side of {@code mcp_access_log} (V29): newest-first admin audit listing
 * with optional service / consumer / window filters. Writes happen on the
 * gateway side (async batch writer), never here.
 */
@Repository
@Transactional(readOnly = true)
public class McpAccessLogRepositoryImpl implements McpAccessLogRepository {

    private static final String COLS = "id, tenant_id, service_id, service_name, consumer_id, consumer_name,"
            + " rpc_method, tool_name, status, http_status, gateway_request_id, occurred_at";

    private static final RowMapper<McpAccessLogEntry> MAPPER = (rs, rowNum) -> new McpAccessLogEntry(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("service_id"),
            rs.getString("service_name"), (UUID) rs.getObject("consumer_id"), rs.getString("consumer_name"),
            rs.getString("rpc_method"), rs.getString("tool_name"), McpAccessStatus.valueOf(rs.getString("status")),
            (Integer) rs.getObject("http_status"), rs.getString("gateway_request_id"),
            rs.getTimestamp("occurred_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public McpAccessLogRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<McpAccessLogEntry> findRecent(UUID tenantId, String serviceName, String consumerName, Instant from,
            Instant to, int limit) {
        return jdbc.query("""
                SELECT
                """ + COLS + """
                        FROM mcp_access_log
                        WHERE tenant_id = :tenantId
                          AND (:serviceName::text IS NULL OR service_name = :serviceName)
                          AND (:consumerName::text IS NULL OR consumer_name = :consumerName)
                          AND occurred_at >= :from
                          AND occurred_at < :to
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :limit
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("serviceName", serviceName)
                        .addValue("consumerName", consumerName).addValue("from", java.sql.Timestamp.from(from))
                        .addValue("to", java.sql.Timestamp.from(to)).addValue("limit", limit),
                MAPPER);
    }
}
