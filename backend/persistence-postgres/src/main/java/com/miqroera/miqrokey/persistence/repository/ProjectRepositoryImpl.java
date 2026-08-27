package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectStatus;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
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
public class ProjectRepositoryImpl implements ProjectRepository {

    private static final RowMapper<Project> ROW_MAPPER = (rs, rowNum) -> new Project((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
            rs.getString("cost_center"), ProjectStatus.valueOf(rs.getString("status")), rs.getString("project_tag"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Project> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM projects WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Project> findByTenantIdAndCode(UUID tenantId, String code) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM projects WHERE tenant_id = :tenantId AND code = :code",
                    new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("code", code), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Project> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM projects WHERE tenant_id = :tenantId ORDER BY code",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public Project insert(Project project) {
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, description, cost_center,
                    status, project_tag, version, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :name, :description, :costCenter,
                    :status, :projectTag, :version, :createdAt, :updatedAt)
                """, toParams(project));
        return project;
    }

    @Override
    @Transactional
    public Project update(Project project) {
        long expectedVersion = project.version() - 1;
        var params = toParams(project).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update("""
                UPDATE projects SET code = :code, name = :name, description = :description,
                    cost_center = :costCenter, status = :status, project_tag = :projectTag,
                    version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: project " + project.id());
        return project;
    }

    @Override
    public boolean existsByTenantIdAndCode(UUID tenantId, String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE tenant_id = :tenantId AND code = :code",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("code", code), Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(Project p) {
        return new MapSqlParameterSource().addValue("id", p.id()).addValue("tenantId", p.tenantId())
                .addValue("code", p.code()).addValue("name", p.name()).addValue("description", p.description())
                .addValue("costCenter", p.costCenter()).addValue("status", p.status().name())
                .addValue("projectTag", p.projectTag()).addValue("version", p.version())
                .addValue("createdAt", Timestamp.from(p.createdAt()))
                .addValue("updatedAt", Timestamp.from(p.updatedAt()));
    }
}
