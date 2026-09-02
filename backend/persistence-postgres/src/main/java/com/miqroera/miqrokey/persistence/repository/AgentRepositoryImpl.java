package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Agent;
import com.miqroera.miqrokey.domain.repository.AgentRepository;
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
public class AgentRepositoryImpl implements AgentRepository {

    private static final RowMapper<Agent> ROW_MAPPER = (rs, rowNum) -> new Agent((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getString("description"),
            (UUID) rs.getObject("upstream_credential_id"), rs.getString("status"), rs.getLong("version"),
            (UUID) rs.getObject("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public AgentRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Agent insert(Agent agent) {
        jdbc.update("""
                INSERT INTO agents
                    (id, tenant_id, name, description, upstream_credential_id, status, version, created_by,
                     created_at, updated_at)
                VALUES (:id, :tenantId, :name, :description, :credentialId, :status, 0, :createdBy, now(), now())
                """,
                new MapSqlParameterSource("id", agent.id()).addValue("tenantId", agent.tenantId())
                        .addValue("name", agent.name()).addValue("description", agent.description())
                        .addValue("credentialId", agent.upstreamCredentialId()).addValue("status", agent.status())
                        .addValue("createdBy", agent.createdBy()));
        return agent;
    }

    @Override
    public Optional<Agent> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional
                    .ofNullable(jdbc.queryForObject("SELECT * FROM agents WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean existsByCredentialId(UUID tenantId, UUID credentialId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE tenant_id = :tenantId AND upstream_credential_id = :credentialId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("credentialId", credentialId), Long.class);
        return count != null && count > 0;
    }

    @Override
    public List<Agent> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM agents WHERE tenant_id = :tenantId ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public Agent updateStatus(UUID tenantId, UUID agentId, String status, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE agents SET status = :status, version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, new MapSqlParameterSource("status", status).addValue("id", agentId).addValue("tenantId", tenantId)
                .addValue("expectedVersion", expectedVersion));
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: agent " + agentId);
        }
        return findByIdAndTenantId(agentId, tenantId).orElseThrow();
    }
}
