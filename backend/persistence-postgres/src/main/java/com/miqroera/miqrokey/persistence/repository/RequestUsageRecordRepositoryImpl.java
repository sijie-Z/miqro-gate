package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.repository.RequestUsageRecordRepository;
import com.miqroera.miqrokey.domain.usage.ModelCallRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side implementation of {@link RequestUsageRecordRepository}.
 *
 * <p>
 * Single-row lookup by {@code (tenant_id, gateway_request_id)} — the same
 * triple the gateway's writer uses as its idempotency key
 * ({@code (started_at, gateway_request_id)}), narrowed by tenant so a request
 * id from one tenant can never be read by another.
 * </p>
 *
 * <p>
 * Never selects content columns (the table has none — prompt, code and model
 * answers are never written to it).
 * </p>
 */
@Repository
@Transactional(readOnly = true)
public class RequestUsageRecordRepositoryImpl implements RequestUsageRecordRepository {

    private static final String SELECT_BY_GATEWAY_REQUEST_ID = """
            -- input/output token columns are normalized the same way the admin usage
            -- list does it (COALESCE over the protocol-specific fallbacks), so the
            -- timeline detail and the row the user clicked never disagree: ~1.5% of
            -- rows (OpenAI Chat protocol) carry only prompt/completion.
            SELECT id, gateway_request_id, upstream_request_id, tenant_id, user_id, project_id, virtual_key_id,
                   provider_id, provider_product_id, credential_id, model_id, wire_protocol, streaming,
                   request_status, started_at, first_byte_at, completed_at, duration_ms, time_to_first_byte_ms,
                   http_status, client_cancelled, partial_response, retry_count,
                   COALESCE(input_tokens, prompt_tokens) AS input_tokens,
                   COALESCE(output_tokens, completion_tokens) AS output_tokens,
                   cache_read_input_tokens, cache_creation_input_tokens
              FROM request_usage_records
             WHERE tenant_id = :tenantId
               AND gateway_request_id = :gatewayRequestId
             ORDER BY started_at DESC
             LIMIT 1
            """;

    private static final RowMapper<ModelCallRecord> MAPPER = (rs, rowNum) -> map(rs);

    private final NamedParameterJdbcTemplate jdbc;

    public RequestUsageRecordRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ModelCallRecord> findByGatewayRequestId(UUID tenantId, String gatewayRequestId) {
        if (tenantId == null || gatewayRequestId == null || gatewayRequestId.isBlank()) {
            return Optional.empty();
        }
        var params = Map.of("tenantId", tenantId, "gatewayRequestId", gatewayRequestId);
        return jdbc.query(SELECT_BY_GATEWAY_REQUEST_ID, params, MAPPER).stream().findFirst();
    }

    private static ModelCallRecord map(ResultSet rs) throws SQLException {
        return new ModelCallRecord(rs.getObject("id", UUID.class), rs.getString("gateway_request_id"),
                rs.getString("upstream_request_id"), rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("virtual_key_id", UUID.class), rs.getObject("provider_id", UUID.class),
                rs.getObject("provider_product_id", UUID.class), rs.getObject("credential_id", UUID.class),
                rs.getString("model_id"), rs.getString("wire_protocol"), rs.getBoolean("streaming"),
                rs.getString("request_status"), toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("first_byte_at")), toInstant(rs.getTimestamp("completed_at")),
                toLong(rs, "duration_ms"), toLong(rs, "time_to_first_byte_ms"), toInteger(rs, "http_status"),
                rs.getBoolean("client_cancelled"), rs.getBoolean("partial_response"), rs.getInt("retry_count"),
                toLong(rs, "input_tokens"), toLong(rs, "output_tokens"), toLong(rs, "cache_read_input_tokens"),
                toLong(rs, "cache_creation_input_tokens"));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Long toLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer toInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
