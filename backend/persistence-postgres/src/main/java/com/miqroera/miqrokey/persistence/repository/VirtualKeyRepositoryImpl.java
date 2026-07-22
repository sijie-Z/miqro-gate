package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
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
            rs.getString("public_key_id"), rs.getBytes("secret_digest"), rs.getString("display_prefix"),
            rs.getString("last_four"), (UUID) rs.getObject("user_id"), (UUID) rs.getObject("project_id"),
            (UUID) rs.getObject("grant_id"), (UUID) rs.getObject("upstream_credential_id"), rs.getString("purpose"),
            rs.getString("name"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toInstant() : null,
            rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null,
            (UUID) rs.getObject("replaced_by_key_id"), rs.getLong("version"));

    private final NamedParameterJdbcTemplate jdbc;

    public VirtualKeyRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VirtualKey> findById(UUID id) {
        var sql = "SELECT * FROM virtual_keys WHERE id = :id";
        var list = jdbc.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<VirtualKey> findByPublicKeyId(String publicKeyId) {
        var sql = "SELECT * FROM virtual_keys WHERE public_key_id = :publicKeyId";
        var params = new MapSqlParameterSource("publicKeyId", publicKeyId);
        var list = jdbc.query(sql, params, ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<VirtualKey> findAllByUserId(UUID userId) {
        var sql = "SELECT * FROM virtual_keys WHERE user_id = :userId ORDER BY created_at DESC";
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), ROW_MAPPER);
    }

    @Override
    public List<VirtualKey> findAllByProjectId(UUID projectId) {
        var sql = "SELECT * FROM virtual_keys WHERE project_id = :projectId ORDER BY created_at DESC";
        return jdbc.query(sql, new MapSqlParameterSource("projectId", projectId), ROW_MAPPER);
    }

    @Override
    public List<VirtualKey> findAllByGrantId(UUID grantId) {
        var sql = "SELECT * FROM virtual_keys WHERE grant_id = :grantId ORDER BY created_at DESC";
        return jdbc.query(sql, new MapSqlParameterSource("grantId", grantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public VirtualKey insert(VirtualKey key) {
        var sql = """
                INSERT INTO virtual_keys (id, public_key_id, secret_digest, display_prefix,
                    last_four, user_id, project_id, grant_id, upstream_credential_id,
                    purpose, name, status, created_at, last_used_at, revoked_at,
                    replaced_by_key_id, version)
                VALUES (:id, :publicKeyId, :secretDigest, :displayPrefix,
                    :lastFour, :userId, :projectId, :grantId, :upstreamCredentialId,
                    :purpose, :name, :status, :createdAt, :lastUsedAt, :revokedAt,
                    :replacedByKeyId, :version)
                """;
        jdbc.update(sql, toParams(key));
        return key;
    }

    @Override
    @Transactional
    public VirtualKey update(VirtualKey key) {
        var sql = """
                UPDATE virtual_keys SET status = :status, last_used_at = :lastUsedAt,
                    revoked_at = :revokedAt, replaced_by_key_id = :replacedByKeyId,
                    version = :version
                WHERE id = :id
                """;
        jdbc.update(sql, toParams(key));
        return key;
    }

    @Override
    public boolean existsByPublicKeyId(String publicKeyId) {
        var sql = "SELECT COUNT(*) FROM virtual_keys WHERE public_key_id = :publicKeyId";
        Integer count = jdbc.queryForObject(sql, new MapSqlParameterSource("publicKeyId", publicKeyId), Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(VirtualKey k) {
        return new MapSqlParameterSource().addValue("id", k.id()).addValue("publicKeyId", k.publicKeyId())
                .addValue("secretDigest", k.secretDigest()).addValue("displayPrefix", k.displayPrefix())
                .addValue("lastFour", k.lastFour()).addValue("userId", k.userId()).addValue("projectId", k.projectId())
                .addValue("grantId", k.grantId()).addValue("upstreamCredentialId", k.upstreamCredentialId())
                .addValue("purpose", k.purpose()).addValue("name", k.name()).addValue("status", k.status())
                .addValue("createdAt", Timestamp.from(k.createdAt()))
                .addValue("lastUsedAt", k.lastUsedAt() != null ? Timestamp.from(k.lastUsedAt()) : null)
                .addValue("revokedAt", k.revokedAt() != null ? Timestamp.from(k.revokedAt()) : null)
                .addValue("replacedByKeyId", k.replacedByKeyId()).addValue("version", k.version());
    }
}
