package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.AdminAuditEvent;
import com.miqroera.miqrokey.domain.repository.AdminAuditEventRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class AdminAuditEventRepositoryImpl implements AdminAuditEventRepository {

    private static final RowMapper<AdminAuditEvent> ROW_MAPPER = (rs, rowNum) -> new AdminAuditEvent(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("actor_id"),
            rs.getString("action"), rs.getString("target_type"), (UUID) rs.getObject("target_id"),
            rs.getString("change_summary"), rs.getString("gateway_request_id"), rs.getString("admin_request_id"),
            rs.getBytes("previous_event_hash"), rs.getBytes("current_event_hash"),
            rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;
    public AdminAuditEventRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AdminAuditEvent insert(AdminAuditEvent event) {
        jdbc.update(
                "INSERT INTO admin_audit_events (id, tenant_id, actor_id, action, target_type, target_id, change_summary, gateway_request_id, admin_request_id, previous_event_hash, current_event_hash, created_at) VALUES (:id, :tenantId, :actorId, :action, :targetType, :targetId, :changeSummary::jsonb, :gatewayRequestId, :adminRequestId, :previousEventHash, :currentEventHash, :createdAt)",
                toParams(event));
        return event;
    }

    @Override
    public List<AdminAuditEvent> findByTargetTypeAndTargetId(String targetType, UUID targetId) {
        return jdbc.query(
                "SELECT * FROM admin_audit_events WHERE target_type = :tt AND target_id = :tid ORDER BY created_at DESC",
                new MapSqlParameterSource().addValue("tt", targetType).addValue("tid", targetId), ROW_MAPPER);
    }

    @Override
    public List<AdminAuditEvent> findByActorId(UUID actorId, int limit) {
        return jdbc.query(
                "SELECT * FROM admin_audit_events WHERE actor_id = :aid ORDER BY created_at DESC LIMIT :limit",
                new MapSqlParameterSource().addValue("aid", actorId).addValue("limit", limit), ROW_MAPPER);
    }

    private MapSqlParameterSource toParams(AdminAuditEvent e) {
        return new MapSqlParameterSource().addValue("id", e.id()).addValue("tenantId", e.tenantId())
                .addValue("actorId", e.actorId()).addValue("action", e.action()).addValue("targetType", e.targetType())
                .addValue("targetId", e.targetId()).addValue("changeSummary", e.changeSummary())
                .addValue("gatewayRequestId", e.gatewayRequestId()).addValue("adminRequestId", e.adminRequestId())
                .addValue("previousEventHash", e.previousEventHash()).addValue("currentEventHash", e.currentEventHash())
                .addValue("createdAt", Timestamp.from(e.createdAt()));
    }
}
