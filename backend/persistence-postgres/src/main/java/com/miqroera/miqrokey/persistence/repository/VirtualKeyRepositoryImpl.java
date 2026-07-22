package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
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
public class VirtualKeyRepositoryImpl implements VirtualKeyRepository {

    private static final RowMapper<VirtualKey> ROW_MAPPER = (rs, rowNum) -> new VirtualKey((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("public_key_id"), rs.getBytes("secret_digest"),
            rs.getString("display_prefix"), rs.getString("last_four"), (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("project_id"), (UUID) rs.getObject("grant_id"),
            (UUID) rs.getObject("upstream_credential_id"), VirtualKeyPurpose.valueOf(rs.getString("purpose")),
            rs.getString("name"), VirtualKeyStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toInstant() : null,
            rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null,
            (UUID) rs.getObject("replaced_by_key_id"), rs.getLong("version"));

    private final NamedParameterJdbcTemplate jdbc;

    public VirtualKeyRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VirtualKey> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM virtual_keys WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<VirtualKey> findByPublicKeyId(String publicKeyId) {
        try {
            return Optional
                    .ofNullable(jdbc.queryForObject("SELECT * FROM virtual_keys WHERE public_key_id = :publicKeyId",
                            new MapSqlParameterSource("publicKeyId", publicKeyId), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<VirtualKey> findAllByUserId(UUID userId) {
        return jdbc.query("SELECT * FROM virtual_keys WHERE user_id = :userId ORDER BY created_at DESC",
                new MapSqlParameterSource("userId", userId), ROW_MAPPER);
    }

    @Override
    public List<VirtualKey> findAllByProjectId(UUID projectId) {
        return jdbc.query("SELECT * FROM virtual_keys WHERE project_id = :projectId ORDER BY created_at DESC",
                new MapSqlParameterSource("projectId", projectId), ROW_MAPPER);
    }

    @Override
    public List<VirtualKey> findAllByGrantId(UUID grantId) {
        return jdbc.query("SELECT * FROM virtual_keys WHERE grant_id = :grantId ORDER BY created_at DESC",
                new MapSqlParameterSource("grantId", grantId), ROW_MAPPER);
    }

    @Override
    public List<VirtualKey> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM virtual_keys WHERE tenant_id = :tenantId ORDER BY created_at DESC",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public VirtualKey insert(VirtualKey key) {
        jdbc.update("""
                INSERT INTO virtual_keys (id, tenant_id, public_key_id, secret_digest, display_prefix,
                    last_four, user_id, project_id, grant_id, upstream_credential_id,
                    purpose, name, status, created_at, last_used_at, revoked_at,
                    replaced_by_key_id, version)
                VALUES (:id, :tenantId, :publicKeyId, :secretDigest, :displayPrefix,
                    :lastFour, :userId, :projectId, :grantId, :upstreamCredentialId,
                    :purpose, :name, :status, :createdAt, :lastUsedAt, :revokedAt,
                    :replacedByKeyId, :version)
                """, toParams(key));
        return key;
    }

    @Override
    @Transactional
    public VirtualKey update(VirtualKey key) {
        long expectedVersion = key.version() - 1;
        var params = toParams(key).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update("""
                UPDATE virtual_keys SET status = :status, last_used_at = :lastUsedAt,
                    revoked_at = :revokedAt, replaced_by_key_id = :replacedByKeyId,
                    version = version + 1
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: virtual key " + key.id());
        return key;
    }

    @Override
    public boolean existsByPublicKeyId(String publicKeyId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM virtual_keys WHERE public_key_id = :publicKeyId",
                new MapSqlParameterSource("publicKeyId", publicKeyId), Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(VirtualKey k) {
        return new MapSqlParameterSource().addValue("id", k.id()).addValue("tenantId", k.tenantId())
                .addValue("publicKeyId", k.publicKeyId()).addValue("secretDigest", k.secretDigest())
                .addValue("displayPrefix", k.displayPrefix()).addValue("lastFour", k.lastFour())
                .addValue("userId", k.userId()).addValue("projectId", k.projectId()).addValue("grantId", k.grantId())
                .addValue("upstreamCredentialId", k.upstreamCredentialId()).addValue("purpose", k.purpose().name())
                .addValue("name", k.name()).addValue("status", k.status().name())
                .addValue("createdAt", Timestamp.from(k.createdAt()))
                .addValue("lastUsedAt", k.lastUsedAt() != null ? Timestamp.from(k.lastUsedAt()) : null)
                .addValue("revokedAt", k.revokedAt() != null ? Timestamp.from(k.revokedAt()) : null)
                .addValue("replacedByKeyId", k.replacedByKeyId()).addValue("version", k.version());
    }
}
