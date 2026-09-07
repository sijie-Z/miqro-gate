package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AuditEventView;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped reads over the append-only admin audit chain (G5.4): newest
 * first in causal order (chain_position DESC), optional exact action filter and
 * {@code beforePosition} cursor. Shared by the session admin endpoint and the
 * open admin API (ADR-0015) so both surfaces slice the same query with the same
 * view.
 */
@Service
public class AuditEventReadService {

    /** Hard cap on rows returned per request. */
    public static final int MAX_SIZE = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditEventReadService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEventView> list(UUID tenantId, int size, String action, Long beforePosition) {
        int limit = Math.min(Math.max(size, 1), MAX_SIZE);
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId).addValue("limit", limit);
        String where = " tenant_id = :tenantId ";
        if (action != null && !action.isBlank()) {
            where += " AND action = :action ";
            params.addValue("action", action);
        }
        if (beforePosition != null) {
            where += " AND chain_position < :beforePosition ";
            params.addValue("beforePosition", beforePosition);
        }
        return jdbc.query("""
                SELECT id, tenant_id, actor_id, action, target_type, target_id, change_summary, created_at,
                       chain_position
                FROM admin_audit_events
                WHERE %s
                ORDER BY chain_position DESC
                LIMIT :limit
                """.formatted(where), params, ROW_MAPPER);
    }

    private static final RowMapper<AuditEventView> ROW_MAPPER = (rs, rowNum) -> new AuditEventView(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("actor_id"), rs.getString("action"),
            rs.getString("target_type"), (UUID) rs.getObject("target_id"), rs.getString("change_summary"),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("chain_position"));
}
