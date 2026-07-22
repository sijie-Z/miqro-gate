package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Tenant;
import com.miqroera.miqrokey.domain.model.TenantStatus;
import com.miqroera.miqrokey.domain.repository.TenantRepository;
import org.springframework.dao.EmptyResultDataAccessException;
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
public class TenantRepositoryImpl implements TenantRepository {

    private static final RowMapper<Tenant> ROW_MAPPER = (rs, rowNum) -> new Tenant((UUID) rs.getObject("id"),
            rs.getString("code"), rs.getString("name"), TenantStatus.valueOf(rs.getString("status")),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public TenantRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Tenant> findById(UUID id) {
        var sql = "SELECT id, code, name, status, version, created_at, updated_at FROM tenants WHERE id = :id";
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Tenant> findByCode(String code) {
        var sql = "SELECT id, code, name, status, version, created_at, updated_at FROM tenants WHERE code = :code";
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, new MapSqlParameterSource("code", code), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Tenant> findAll() {
        return jdbc.query("SELECT id, code, name, status, version, created_at, updated_at FROM tenants ORDER BY code",
                ROW_MAPPER);
    }

    @Override
    @Transactional
    public Tenant insert(Tenant tenant) {
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                VALUES (:id, :code, :name, :status, :version, :createdAt, :updatedAt)
                """, toParams(tenant));
        return tenant;
    }

    @Override
    @Transactional
    public Tenant update(Tenant tenant) {
        long expectedVersion = tenant.version() - 1;
        var params = toParams(tenant).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update("""
                UPDATE tenants SET code = :code, name = :name, status = :status,
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """, params);
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: tenant " + tenant.id() + " version mismatch");
        }
        return tenant;
    }

    @Override
    public boolean existsByCode(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE code = :code",
                new MapSqlParameterSource("code", code), Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(Tenant t) {
        return new MapSqlParameterSource().addValue("id", t.id()).addValue("code", t.code()).addValue("name", t.name())
                .addValue("status", t.status().name()).addValue("version", t.version())
                .addValue("createdAt", Timestamp.from(t.createdAt()))
                .addValue("updatedAt", Timestamp.from(t.updatedAt()));
    }
}
