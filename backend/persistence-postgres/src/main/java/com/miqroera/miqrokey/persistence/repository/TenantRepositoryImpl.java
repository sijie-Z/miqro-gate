package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Tenant;
import com.miqroera.miqrokey.domain.repository.TenantRepository;
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
            rs.getString("code"), rs.getString("name"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public TenantRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Tenant> findById(UUID id) {
        var sql = "SELECT id, code, name, status, created_at, updated_at FROM tenants WHERE id = :id";
        var params = new MapSqlParameterSource("id", id);
        var list = jdbc.query(sql, params, ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<Tenant> findByCode(String code) {
        var sql = "SELECT id, code, name, status, created_at, updated_at FROM tenants WHERE code = :code";
        var params = new MapSqlParameterSource("code", code);
        var list = jdbc.query(sql, params, ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<Tenant> findAll() {
        var sql = "SELECT id, code, name, status, created_at, updated_at FROM tenants ORDER BY code";
        return jdbc.query(sql, ROW_MAPPER);
    }

    @Override
    @Transactional
    public Tenant insert(Tenant tenant) {
        var sql = """
                INSERT INTO tenants (id, code, name, status, created_at, updated_at)
                VALUES (:id, :code, :name, :status, :createdAt, :updatedAt)
                """;
        var params = toParams(tenant);
        jdbc.update(sql, params);
        return tenant;
    }

    @Override
    @Transactional
    public Tenant update(Tenant tenant) {
        var sql = """
                UPDATE tenants SET code = :code, name = :name, status = :status,
                    updated_at = :updatedAt
                WHERE id = :id
                """;
        var params = toParams(tenant);
        jdbc.update(sql, params);
        return tenant;
    }

    @Override
    public boolean existsByCode(String code) {
        var sql = "SELECT COUNT(*) FROM tenants WHERE code = :code";
        var params = new MapSqlParameterSource("code", code);
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(Tenant t) {
        return new MapSqlParameterSource().addValue("id", t.id()).addValue("code", t.code()).addValue("name", t.name())
                .addValue("status", t.status()).addValue("createdAt", Timestamp.from(t.createdAt()))
                .addValue("updatedAt", Timestamp.from(t.updatedAt()));
    }
}
