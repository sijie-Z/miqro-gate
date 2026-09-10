package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.InternalService;
import com.miqroera.miqrokey.domain.repository.InternalServiceRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class InternalServiceRepositoryImpl implements InternalServiceRepository {

    private static final RowMapper<InternalService> ROW_MAPPER = (rs, rowNum) -> new InternalService(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("kind"),
            rs.getString("description"), rs.getString("base_url"), rs.getString("status"), rs.getLong("version"),
            (UUID) rs.getObject("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), rs.getString("health_status"),
            rs.getTimestamp("health_checked_at") != null ? rs.getTimestamp("health_checked_at").toInstant() : null,
            rs.getInt("consecutive_failures"), rs.getInt("consecutive_successes"), rs.getInt("check_interval_seconds"),
            rs.getInt("check_timeout_seconds"), rs.getInt("fail_threshold"), rs.getInt("recover_threshold"),
            rs.getString("check_path"));

    private final NamedParameterJdbcTemplate jdbc;

    public InternalServiceRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public InternalService insert(InternalService service) {
        jdbc.update("""
                INSERT INTO services
                    (id, tenant_id, name, kind, description, base_url, status, version, created_by, created_at,
                     updated_at, health_status, health_checked_at, consecutive_failures, consecutive_successes,
                     check_interval_seconds, check_timeout_seconds, fail_threshold, recover_threshold, check_path)
                VALUES (:id, :tenantId, :name, :kind, :description, :baseUrl, :status, 0, :createdBy, now(), now(),
                        :healthStatus, :checkedAt, :failures, :successes, :interval, :timeout, :failThreshold,
                        :recoverThreshold, :checkPath)
                """, params(service));
        return service;
    }

    @Override
    public Optional<InternalService> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional
                    .ofNullable(jdbc.queryForObject("SELECT * FROM services WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<InternalService> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM services WHERE tenant_id = :tenantId ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    public List<InternalService> findAllActiveByTenantId(UUID tenantId) {
        return jdbc.query(
                "SELECT * FROM services WHERE tenant_id = :tenantId AND status = 'ACTIVE' ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public InternalService updateStatus(UUID tenantId, UUID serviceId, String status, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE services SET status = :status, version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, new MapSqlParameterSource("status", status).addValue("id", serviceId)
                .addValue("tenantId", tenantId).addValue("expectedVersion", expectedVersion));
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: service " + serviceId);
        }
        return findByIdAndTenantId(serviceId, tenantId).orElseThrow();
    }

    @Override
    @Transactional
    public InternalService update(InternalService service, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE services
                SET description = :description, base_url = :baseUrl, status = :status, health_status = :healthStatus,
                    health_checked_at = :checkedAt, consecutive_failures = :failures,
                    consecutive_successes = :successes, check_interval_seconds = :interval,
                    check_timeout_seconds = :timeout, fail_threshold = :failThreshold,
                    recover_threshold = :recoverThreshold, check_path = :checkPath, version = version + 1,
                    updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, params(service).addValue("expectedVersion", expectedVersion));
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: service " + service.id());
        }
        return findByIdAndTenantId(service.id(), service.tenantId()).orElseThrow();
    }

    private static MapSqlParameterSource params(InternalService s) {
        return new MapSqlParameterSource("id", s.id()).addValue("tenantId", s.tenantId()).addValue("name", s.name())
                .addValue("kind", s.kind()).addValue("description", s.description()).addValue("baseUrl", s.baseUrl())
                .addValue("status", s.status()).addValue("createdBy", s.createdBy())
                .addValue("healthStatus", s.healthStatus())
                .addValue("checkedAt",
                        s.healthCheckedAt() != null ? java.sql.Timestamp.from(s.healthCheckedAt()) : null)
                .addValue("failures", s.consecutiveFailures()).addValue("successes", s.consecutiveSuccesses())
                .addValue("interval", s.checkIntervalSeconds()).addValue("timeout", s.checkTimeoutSeconds())
                .addValue("failThreshold", s.failThreshold()).addValue("recoverThreshold", s.recoverThreshold())
                .addValue("checkPath", s.checkPath());
    }
}
