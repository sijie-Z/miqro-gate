package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.KeyProjectBinding;
import com.miqroera.miqrokey.domain.model.KeyProjectBindingStatus;
import com.miqroera.miqrokey.domain.repository.KeyProjectBindingRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class KeyProjectBindingRepositoryImpl implements KeyProjectBindingRepository {

    private static final RowMapper<KeyProjectBinding> ROW_MAPPER = (rs, rowNum) -> new KeyProjectBinding(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("virtual_key_id"),
            (UUID) rs.getObject("project_id"), KeyProjectBindingStatus.valueOf(rs.getString("status")),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public KeyProjectBindingRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<KeyProjectBinding> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM key_project_binding WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<KeyProjectBinding> findByVirtualKeyId(UUID virtualKeyId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM key_project_binding WHERE virtual_key_id = :virtualKeyId ORDER BY created_at DESC LIMIT 1",
                    new MapSqlParameterSource("virtualKeyId", virtualKeyId), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public KeyProjectBinding insert(KeyProjectBinding binding) {
        jdbc.update("""
                INSERT INTO key_project_binding (id, tenant_id, virtual_key_id, project_id,
                    status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :virtualKeyId, :projectId,
                    :status, :version, :createdAt, :updatedAt)
                """, toParams(binding));
        return binding;
    }

    @Override
    @Transactional
    public KeyProjectBinding update(KeyProjectBinding binding) {
        long expectedVersion = binding.version() - 1;
        var params = toParams(binding).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update("""
                UPDATE key_project_binding SET status = :status, version = version + 1,
                    updated_at = :updatedAt
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: key project binding " + binding.id());
        return binding;
    }

    private MapSqlParameterSource toParams(KeyProjectBinding b) {
        return new MapSqlParameterSource().addValue("id", b.id()).addValue("tenantId", b.tenantId())
                .addValue("virtualKeyId", b.virtualKeyId()).addValue("projectId", b.projectId())
                .addValue("status", b.status().name()).addValue("version", b.version())
                .addValue("createdAt", Timestamp.from(b.createdAt()))
                .addValue("updatedAt", Timestamp.from(b.updatedAt()));
    }
}
