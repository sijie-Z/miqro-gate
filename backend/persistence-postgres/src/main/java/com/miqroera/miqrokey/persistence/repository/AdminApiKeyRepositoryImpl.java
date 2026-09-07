package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class AdminApiKeyRepositoryImpl implements AdminApiKeyRepository {

    private static final RowMapper<AdminApiKey> ROW_MAPPER = (rs, rowNum) -> new AdminApiKey((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getBytes("key_digest"),
            rs.getString("key_prefix"), (UUID) rs.getObject("created_by"), timestamp(rs, "expires_at"),
            timestamp(rs, "revoked_at"), rs.getTimestamp("created_at").toInstant());

    private static Instant timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value != null ? value.toInstant() : null;
    }

    private final NamedParameterJdbcTemplate jdbc;

    public AdminApiKeyRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AdminApiKey insert(AdminApiKey key) {
        jdbc.update("""
                INSERT INTO admin_api_keys
                    (id, tenant_id, name, key_digest, key_prefix, created_by, expires_at, revoked_at, created_at)
                VALUES (:id, :tenantId, :name, :keyDigest, :keyPrefix, :createdBy, :expiresAt, null, now())
                """,
                new MapSqlParameterSource("id", key.id()).addValue("tenantId", key.tenantId())
                        .addValue("name", key.name()).addValue("keyDigest", key.keyDigest())
                        .addValue("keyPrefix", key.keyPrefix()).addValue("createdBy", key.createdBy())
                        .addValue("expiresAt", key.expiresAt() != null ? Timestamp.from(key.expiresAt()) : null));
        return key;
    }

    @Override
    public List<AdminApiKey> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM admin_api_keys WHERE tenant_id = :tenantId ORDER BY created_at DESC",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    public Optional<AdminApiKey> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT * FROM admin_api_keys WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AdminApiKey> findActiveByDigest(byte[] keyDigest) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM admin_api_keys WHERE key_digest = :keyDigest AND revoked_at IS NULL",
                    new MapSqlParameterSource("keyDigest", keyDigest), ROW_MAPPER));
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
}
