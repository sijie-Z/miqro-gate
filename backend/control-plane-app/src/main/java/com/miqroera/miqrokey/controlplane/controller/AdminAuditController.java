package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.security.UserContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin audit events (G5.4, api-contract §5): immutable chain-links in reverse
 * causal order, with optional action filtering. The chain hashes are never
 * serialized (integrity proof lives in the table, not the API).
 * SYSTEM_ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-events")
public class AdminAuditController {

    private static final int MAX_SIZE = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final UserContext userContext;

    public AdminAuditController(NamedParameterJdbcTemplate jdbc, UserContext userContext) {
        this.jdbc = jdbc;
        this.userContext = userContext;
    }

    @GetMapping
    public List<AuditEventView> list(@RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action, @RequestParam(required = false) Long beforePosition) {
        UUID tenantId = userContext.getUser().tenantId();
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

    public record AuditEventView(UUID id, UUID actorId, String action, String targetType, UUID targetId,
            String changeSummary, Instant createdAt, long chainPosition) {
    }

    private static final RowMapper<AuditEventView> ROW_MAPPER = (rs, rowNum) -> new AuditEventView(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("actor_id"), rs.getString("action"),
            rs.getString("target_type"), (UUID) rs.getObject("target_id"), rs.getString("change_summary"),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("chain_position"));
}
