package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
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
public class ProjectRepositoryImpl implements ProjectRepository {

    private static final RowMapper<Project> ROW_MAPPER = (rs, rowNum) -> new Project((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
            rs.getString("cost_center"), rs.getString("status"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Project> findById(UUID id) {
        var sql = "SELECT * FROM projects WHERE id = :id";
        var list = jdbc.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<Project> findByTenantIdAndCode(UUID tenantId, String code) {
        var sql = "SELECT * FROM projects WHERE tenant_id = :tenantId AND code = :code";
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("code", code);
        var list = jdbc.query(sql, params, ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<Project> findAllByTenantId(UUID tenantId) {
        var sql = "SELECT * FROM projects WHERE tenant_id = :tenantId ORDER BY code";
        return jdbc.query(sql, new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public Project insert(Project project) {
        var sql = """
                INSERT INTO projects (id, tenant_id, code, name, description, cost_center,
                    status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :name, :description, :costCenter,
                    :status, :version, :createdAt, :updatedAt)
                """;
        jdbc.update(sql, toParams(project));
        return project;
    }

    @Override
    @Transactional
    public Project update(Project project) {
        var sql = """
                UPDATE projects SET code = :code, name = :name, description = :description,
                    cost_center = :costCenter, status = :status, version = :version,
                    updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbc.update(sql, toParams(project));
        return project;
    }

    @Override
    public boolean existsByTenantIdAndCode(UUID tenantId, String code) {
        var sql = "SELECT COUNT(*) FROM projects WHERE tenant_id = :tenantId AND code = :code";
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("code", code);
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(Project p) {
        return new MapSqlParameterSource().addValue("id", p.id()).addValue("tenantId", p.tenantId())
                .addValue("code", p.code()).addValue("name", p.name()).addValue("description", p.description())
                .addValue("costCenter", p.costCenter()).addValue("status", p.status()).addValue("version", p.version())
                .addValue("createdAt", Timestamp.from(p.createdAt()))
                .addValue("updatedAt", Timestamp.from(p.updatedAt()));
    }
}
