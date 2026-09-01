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
            rs.getTimestamp("updated_at").toInstant());

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
                     updated_at)
                VALUES (:id, :tenantId, :name, :kind, :description, :baseUrl, :status, 0, :createdBy, now(), now())
                """,
                new MapSqlParameterSource("id", service.id()).addValue("tenantId", service.tenantId())
                        .addValue("name", service.name()).addValue("kind", service.kind())
                        .addValue("description", service.description()).addValue("baseUrl", service.baseUrl())
                        .addValue("status", service.status()).addValue("createdBy", service.createdBy()));
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
}
