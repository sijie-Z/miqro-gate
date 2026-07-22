package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.ProjectMembership;
import com.miqroera.miqrokey.domain.repository.ProjectMembershipRepository;
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
public class ProjectMembershipRepositoryImpl implements ProjectMembershipRepository {

    private static final RowMapper<ProjectMembership> ROW_MAPPER = (rs, rowNum) -> new ProjectMembership(
            (UUID) rs.getObject("project_id"), (UUID) rs.getObject("user_id"), (UUID) rs.getObject("created_by"),
            rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectMembershipRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ProjectMembership> findAllByProjectId(UUID projectId) {
        var sql = "SELECT * FROM project_memberships WHERE project_id = :projectId";
        return jdbc.query(sql, new MapSqlParameterSource("projectId", projectId), ROW_MAPPER);
    }

    @Override
    public List<ProjectMembership> findAllByUserId(UUID userId) {
        var sql = "SELECT * FROM project_memberships WHERE user_id = :userId";
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public ProjectMembership insert(ProjectMembership membership) {
        var sql = """
                INSERT INTO project_memberships (project_id, user_id, created_by, created_at)
                VALUES (:projectId, :userId, :createdBy, :createdAt)
                """;
        jdbc.update(sql, toParams(membership));
        return membership;
    }

    @Override
    @Transactional
    public void delete(UUID projectId, UUID userId) {
        var sql = "DELETE FROM project_memberships WHERE project_id = :projectId AND user_id = :userId";
        var params = new MapSqlParameterSource().addValue("projectId", projectId).addValue("userId", userId);
        jdbc.update(sql, params);
    }

    @Override
    public boolean exists(UUID projectId, UUID userId) {
        var sql = "SELECT COUNT(*) FROM project_memberships WHERE project_id = :projectId AND user_id = :userId";
        var params = new MapSqlParameterSource().addValue("projectId", projectId).addValue("userId", userId);
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(ProjectMembership m) {
        return new MapSqlParameterSource().addValue("projectId", m.projectId()).addValue("userId", m.userId())
                .addValue("createdBy", m.createdBy()).addValue("createdAt", Timestamp.from(m.createdAt()));
    }
}
