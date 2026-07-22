package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
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
public class ProjectProviderGrantRepositoryImpl implements ProjectProviderGrantRepository {

    private static final RowMapper<ProjectProviderGrant> ROW_MAPPER = (rs, rowNum) -> new ProjectProviderGrant(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"), (UUID) rs.getObject("provider_product_id"),
            (UUID) rs.getObject("upstream_credential_id"), rs.getString("status"), (UUID) rs.getObject("created_by"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectProviderGrantRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectProviderGrant> findById(UUID id) {
        var sql = "SELECT * FROM project_provider_grants WHERE id = :id";
        var list = jdbc.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<ProjectProviderGrant> findAllByProjectId(UUID projectId) {
        var sql = "SELECT * FROM project_provider_grants WHERE project_id = :projectId";
        return jdbc.query(sql, new MapSqlParameterSource("projectId", projectId), ROW_MAPPER);
    }

    @Override
    public List<ProjectProviderGrant> findAllByProjectIdAndStatus(UUID projectId, String status) {
        var sql = "SELECT * FROM project_provider_grants WHERE project_id = :projectId AND status = :status";
        var params = new MapSqlParameterSource().addValue("projectId", projectId).addValue("status", status);
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    @Override
    public List<ProjectProviderGrant> findAllByCredentialId(UUID credentialId) {
        var sql = "SELECT * FROM project_provider_grants WHERE upstream_credential_id = :credentialId";
        return jdbc.query(sql, new MapSqlParameterSource("credentialId", credentialId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public ProjectProviderGrant insert(ProjectProviderGrant grant) {
        var sql = """
                INSERT INTO project_provider_grants (id, project_id, provider_product_id,
                    upstream_credential_id, status, created_by, version, created_at, updated_at)
                VALUES (:id, :projectId, :providerProductId,
                    :upstreamCredentialId, :status, :createdBy, :version, :createdAt, :updatedAt)
                """;
        jdbc.update(sql, toParams(grant));
        return grant;
    }

    @Override
    @Transactional
    public ProjectProviderGrant update(ProjectProviderGrant grant) {
        var sql = """
                UPDATE project_provider_grants SET status = :status, version = :version,
                    updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbc.update(sql, toParams(grant));
        return grant;
    }

    @Override
    public boolean existsByProjectIdAndProductIdAndCredentialId(UUID projectId, UUID providerProductId,
            UUID credentialId) {
        var sql = """
                SELECT COUNT(*) FROM project_provider_grants
                WHERE project_id = :projectId
                  AND provider_product_id = :productId
                  AND upstream_credential_id = :credentialId
                """;
        var params = new MapSqlParameterSource().addValue("projectId", projectId)
                .addValue("productId", providerProductId).addValue("credentialId", credentialId);
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(ProjectProviderGrant g) {
        return new MapSqlParameterSource().addValue("id", g.id()).addValue("projectId", g.projectId())
                .addValue("providerProductId", g.providerProductId())
                .addValue("upstreamCredentialId", g.upstreamCredentialId()).addValue("status", g.status())
                .addValue("createdBy", g.createdBy()).addValue("version", g.version())
                .addValue("createdAt", Timestamp.from(g.createdAt()))
                .addValue("updatedAt", Timestamp.from(g.updatedAt()));
    }
}
