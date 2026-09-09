package com.miqroera.miqrokey.persistence.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class AdminApiKeyRepositoryImpl implements AdminApiKeyRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<AdminApiKey> rowMapper;

    public AdminApiKeyRepositoryImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = (rs, rowNum) -> new AdminApiKey((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                rs.getString("name"), rs.getBytes("key_digest"), rs.getString("key_prefix"),
                (UUID) rs.getObject("created_by"), timestamp(rs, "expires_at"), timestamp(rs, "revoked_at"),
                rs.getTimestamp("created_at").toInstant(), capabilities(rs));
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value != null ? value.toInstant() : null;
    }

    /** Reads the nullable jsonb scope column as a capability list (null = full). */
    private List<String> capabilities(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("scope");
        if (raw == null) {
            return null;
        }
        String text = raw instanceof org.postgresql.util.PGobject pg ? pg.getValue() : String.valueOf(raw);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, new TypeReference<List<String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable admin_api_keys.scope json for key row", e);
        }
    }

    @Override
    @Transactional
    public AdminApiKey insert(AdminApiKey key) {
        jdbc.update(
                """
                        INSERT INTO admin_api_keys
                            (id, tenant_id, name, key_digest, key_prefix, created_by, expires_at, revoked_at, created_at, scope)
                        VALUES (:id, :tenantId, :name, :keyDigest, :keyPrefix, :createdBy, :expiresAt, null, now(), :scope::jsonb)
                        """,
                new MapSqlParameterSource("id", key.id()).addValue("tenantId", key.tenantId())
                        .addValue("name", key.name()).addValue("keyDigest", key.keyDigest())
                        .addValue("keyPrefix", key.keyPrefix()).addValue("createdBy", key.createdBy())
                        .addValue("expiresAt", key.expiresAt() != null ? Timestamp.from(key.expiresAt()) : null)
                        .addValue("scope", scopeJson(key.capabilities())));
        return key;
    }

    @Override
    public List<AdminApiKey> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM admin_api_keys WHERE tenant_id = :tenantId ORDER BY created_at DESC",
                new MapSqlParameterSource("tenantId", tenantId), rowMapper);
    }

    @Override
    public Optional<AdminApiKey> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT * FROM admin_api_keys WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), rowMapper));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AdminApiKey> findActiveByDigest(byte[] keyDigest) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM admin_api_keys WHERE key_digest = :keyDigest AND revoked_at IS NULL",
                    new MapSqlParameterSource("keyDigest", keyDigest), rowMapper));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public boolean revoke(UUID id, UUID tenantId, Instant revokedAt) {
        int rows = jdbc.update("""
                UPDATE admin_api_keys SET revoked_at = :revokedAt
                WHERE id = :id AND tenant_id = :tenantId AND revoked_at IS NULL
                """, new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("revokedAt",
                Timestamp.from(revokedAt)));
        return rows == 1;
    }

    @Override
    public List<AdminApiKey> findActiveExpiringBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM admin_api_keys WHERE tenant_id = :tenantId AND revoked_at IS NULL"
                        + " AND expires_at >= :from AND expires_at <= :to ORDER BY expires_at",
                new MapSqlParameterSource("tenantId", tenantId).addValue("from", Timestamp.from(from)).addValue("to",
                        Timestamp.from(to)),
                rowMapper);
    }

    @Override
    @Transactional
    public boolean updateScope(UUID id, UUID tenantId, List<String> capabilities) {
        int rows = jdbc.update("""
                UPDATE admin_api_keys SET scope = :scope::jsonb
                WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("scope",
                scopeJson(capabilities)));
        return rows == 1;
    }

    /** Serializes the capability list; null scope stays NULL (full access). */
    private String scopeJson(List<String> capabilities) {
        if (capabilities == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(capabilities);
        } catch (IOException e) {
            throw new IllegalStateException("Unserializable admin api key scope", e);
        }
    }
}
