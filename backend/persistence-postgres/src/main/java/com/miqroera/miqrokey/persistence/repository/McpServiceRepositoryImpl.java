package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.McpService;
import com.miqroera.miqrokey.domain.repository.McpServiceRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class McpServiceRepositoryImpl implements McpServiceRepository {

    private static final RowMapper<McpService> ROW_MAPPER = (rs, rowNum) -> new McpService((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("description"),
            rs.getString("endpoint"), rs.getString("transport"), rs.getString("status"), rs.getString("health_status"),
            rs.getTimestamp("health_checked_at") != null ? rs.getTimestamp("health_checked_at").toInstant() : null,
            rs.getInt("consecutive_failures"), rs.getInt("consecutive_successes"), rs.getInt("check_interval_seconds"),
            rs.getInt("check_timeout_seconds"), rs.getInt("fail_threshold"), rs.getInt("recover_threshold"),
            rs.getString("check_path"), rs.getLong("version"), (UUID) rs.getObject("created_by"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public McpServiceRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public McpService insert(McpService service) {
        jdbc.update("""
                INSERT INTO mcp_services
                    (id, tenant_id, name, description, endpoint, transport, status, health_status,
                     health_checked_at, consecutive_failures, consecutive_successes, check_interval_seconds,
                     check_timeout_seconds, fail_threshold, recover_threshold, check_path, version, created_by,
                     created_at, updated_at)
                VALUES (:id, :tenantId, :name, :description, :endpoint, :transport, :status, :healthStatus,
                        :checkedAt, 0, 0, :interval, :timeout, :failThreshold, :recoverThreshold, :checkPath, 0,
                        :createdBy, now(), now())
                """, params(service));
        return service;
    }

    @Override
    public Optional<McpService> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT * FROM mcp_services WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<McpService> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM mcp_services WHERE tenant_id = :tenantId ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    public List<McpService> findAllOnlineByTenantId(UUID tenantId) {
        return jdbc.query(
                "SELECT * FROM mcp_services WHERE tenant_id = :tenantId AND status = 'ONLINE' ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public McpService update(McpService service, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mcp_services
                SET description = :description, endpoint = :endpoint, transport = :transport, status = :status,
                    health_status = :healthStatus, health_checked_at = :checkedAt,
                    consecutive_failures = :failures, consecutive_successes = :successes,
                    check_interval_seconds = :interval, check_timeout_seconds = :timeout,
                    fail_threshold = :failThreshold, recover_threshold = :recoverThreshold, check_path = :checkPath,
                    version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, params(service).addValue("expectedVersion", expectedVersion));
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: mcp service " + service.id());
        }
        return findByIdAndTenantId(service.id(), service.tenantId()).orElseThrow();
    }

    private static MapSqlParameterSource params(McpService s) {
        return new MapSqlParameterSource("id", s.id()).addValue("tenantId", s.tenantId()).addValue("name", s.name())
                .addValue("description", s.description()).addValue("endpoint", s.endpoint())
                .addValue("transport", s.transport()).addValue("status", s.status())
                .addValue("healthStatus", s.healthStatus())
                .addValue("checkedAt", s.healthCheckedAt() != null ? Timestamp.from(s.healthCheckedAt()) : null)
                .addValue("failures", s.consecutiveFailures()).addValue("successes", s.consecutiveSuccesses())
                .addValue("interval", s.checkIntervalSeconds()).addValue("timeout", s.checkTimeoutSeconds())
                .addValue("failThreshold", s.failThreshold()).addValue("recoverThreshold", s.recoverThreshold())
                .addValue("checkPath", s.checkPath()).addValue("createdBy", s.createdBy());
    }
}
