package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.GrantStatus;
import com.miqroera.miqrokey.domain.model.ProjectProviderGrant;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class ProjectProviderGrantRepositoryImpl implements ProjectProviderGrantRepository {

    private static final RowMapper<ProjectProviderGrant> ROW_MAPPER = (rs, rowNum) -> new ProjectProviderGrant(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("project_id"),
            (UUID) rs.getObject("provider_product_id"), (UUID) rs.getObject("upstream_credential_id"),
            GrantStatus.valueOf(rs.getString("status")), (UUID) rs.getObject("created_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectProviderGrantRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectProviderGrant> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM project_provider_grants WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ProjectProviderGrant> findAllByProjectId(UUID projectId) {
        return jdbc.query("SELECT * FROM project_provider_grants WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId), ROW_MAPPER);
    }

    @Override
    public List<ProjectProviderGrant> findAllByProjectIdAndStatus(UUID projectId, String status) {
        return jdbc.query("SELECT * FROM project_provider_grants WHERE project_id = :projectId AND status = :status",
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("status", status), ROW_MAPPER);
    }

    @Override
    public List<ProjectProviderGrant> findAllByCredentialId(UUID credentialId) {
        return jdbc.query("SELECT * FROM project_provider_grants WHERE upstream_credential_id = :credentialId",
                new MapSqlParameterSource("credentialId", credentialId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public ProjectProviderGrant insert(ProjectProviderGrant grant) {
        jdbc.update(
                "INSERT INTO project_provider_grants (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by, version, created_at, updated_at) VALUES (:id, :tenantId, :projectId, :providerProductId, :upstreamCredentialId, :status, :createdBy, :version, :createdAt, :updatedAt)",
                toParams(grant));
        return grant;
    }

    @Override
    @Transactional
    public ProjectProviderGrant update(ProjectProviderGrant grant) {
        long expectedVersion = grant.version() - 1;
        var params = toParams(grant).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update(
                "UPDATE project_provider_grants SET status = :status, version = version + 1, updated_at = :updatedAt WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion",
                params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: grant " + grant.id());
        return grant;
    }

    @Override
    public boolean existsByProjectIdAndProductIdAndCredentialId(UUID projectId, UUID providerProductId,
            UUID credentialId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_provider_grants WHERE project_id = :projectId AND provider_product_id = :productId AND upstream_credential_id = :credentialId",
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("productId", providerProductId)
                        .addValue("credentialId", credentialId),
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public Set<String> findModelIds(UUID grantId) {
        List<String> models = jdbc.queryForList(
                "SELECT model_id FROM project_provider_grant_models WHERE grant_id = :grantId ORDER BY model_id",
                new MapSqlParameterSource("grantId", grantId), String.class);
        return Set.copyOf(models);
    }

    private MapSqlParameterSource toParams(ProjectProviderGrant g) {
        return new MapSqlParameterSource().addValue("id", g.id()).addValue("tenantId", g.tenantId())
                .addValue("projectId", g.projectId()).addValue("providerProductId", g.providerProductId())
                .addValue("upstreamCredentialId", g.upstreamCredentialId()).addValue("status", g.status().name())
                .addValue("createdBy", g.createdBy()).addValue("version", g.version())
                .addValue("createdAt", Timestamp.from(g.createdAt()))
                .addValue("updatedAt", Timestamp.from(g.updatedAt()));
    }
}
