package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Team;
import com.miqroera.miqrokey.domain.model.TeamStatus;
import com.miqroera.miqrokey.domain.repository.TeamRepository;
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
public class TeamRepositoryImpl implements TeamRepository {

    private static final RowMapper<Team> ROW_MAPPER = (rs, rowNum) -> new Team((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("description"),
            TeamStatus.valueOf(rs.getString("status")), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;
    public TeamRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Team> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM teams WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Team> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM teams WHERE tenant_id = :tenantId ORDER BY name",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public Team insert(Team team) {
        jdbc.update(
                "INSERT INTO teams (id, tenant_id, name, description, status, version, created_at, updated_at) VALUES (:id, :tenantId, :name, :description, :status, :version, :createdAt, :updatedAt)",
                toParams(team));
        return team;
    }

    @Override
    @Transactional
    public Team update(Team team) {
        long expectedVersion = team.version() - 1;
        var params = toParams(team).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update(
                "UPDATE teams SET name = :name, description = :description, status = :status, version = version + 1, updated_at = :updatedAt WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion",
                params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: team " + team.id());
        return team;
    }

    private MapSqlParameterSource toParams(Team t) {
        return new MapSqlParameterSource().addValue("id", t.id()).addValue("tenantId", t.tenantId())
                .addValue("name", t.name()).addValue("description", t.description())
                .addValue("status", t.status().name()).addValue("version", t.version())
                .addValue("createdAt", Timestamp.from(t.createdAt()))
                .addValue("updatedAt", Timestamp.from(t.updatedAt()));
    }
}
