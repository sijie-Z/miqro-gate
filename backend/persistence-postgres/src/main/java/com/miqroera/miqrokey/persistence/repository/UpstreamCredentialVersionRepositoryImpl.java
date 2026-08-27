package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.CredentialVersionStatus;
import com.miqroera.miqrokey.domain.model.UpstreamCredentialVersion;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialVersionRepository;
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
public class UpstreamCredentialVersionRepositoryImpl implements UpstreamCredentialVersionRepository {

    private static final RowMapper<UpstreamCredentialVersion> ROW_MAPPER = (rs,
            rowNum) -> new UpstreamCredentialVersion((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                    (UUID) rs.getObject("credential_id"), rs.getBytes("encrypted_secret"), rs.getBytes("nonce"),
                    rs.getString("encryption_key_version"), rs.getBytes("secret_fingerprint"),
                    CredentialVersionStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("valid_from") != null ? rs.getTimestamp("valid_from").toInstant() : null,
                    rs.getTimestamp("retired_at") != null ? rs.getTimestamp("retired_at").toInstant() : null,
                    rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;
    public UpstreamCredentialVersionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UpstreamCredentialVersion> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM upstream_credential_versions WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<UpstreamCredentialVersion> findAllByCredentialId(UUID credentialId) {
        return jdbc.query(
                "SELECT * FROM upstream_credential_versions WHERE credential_id = :cid ORDER BY created_at DESC",
                new MapSqlParameterSource("cid", credentialId), ROW_MAPPER);
    }

    @Override
    public Optional<UpstreamCredentialVersion> findActiveByCredentialId(UUID credentialId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM upstream_credential_versions WHERE credential_id = :cid AND status = 'ACTIVE'",
                    new MapSqlParameterSource("cid", credentialId), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public UpstreamCredentialVersion insert(UpstreamCredentialVersion version) {
        jdbc.update(
                "INSERT INTO upstream_credential_versions (id, tenant_id, credential_id, encrypted_secret, nonce, encryption_key_version, secret_fingerprint, status, valid_from, retired_at, created_at) VALUES (:id, :tenantId, :credentialId, :encryptedSecret, :nonce, :encryptionKeyVersion, :secretFingerprint, :status, :validFrom, :retiredAt, :createdAt)",
                toParams(version));
        return version;
    }

    @Override
    @Transactional
    public UpstreamCredentialVersion update(UpstreamCredentialVersion version) {
        // Credential versions are immutable except for status transitions
        int rows = jdbc.update(
                "UPDATE upstream_credential_versions SET status = :status, retired_at = :retiredAt WHERE id = :id AND tenant_id = :tenantId",
                toParams(version));
        if (rows != 1)
            throw new IllegalStateException("Credential version update failed: " + version.id());
        return version;
    }

    private MapSqlParameterSource toParams(UpstreamCredentialVersion v) {
        return new MapSqlParameterSource().addValue("id", v.id()).addValue("tenantId", v.tenantId())
                .addValue("credentialId", v.credentialId()).addValue("encryptedSecret", v.encryptedSecret())
                .addValue("nonce", v.nonce()).addValue("encryptionKeyVersion", v.encryptionKeyVersion())
                .addValue("secretFingerprint", v.secretFingerprint()).addValue("status", v.status().name())
                .addValue("validFrom", v.validFrom() != null ? Timestamp.from(v.validFrom()) : null)
                .addValue("retiredAt", v.retiredAt() != null ? Timestamp.from(v.retiredAt()) : null)
                .addValue("createdAt", Timestamp.from(v.createdAt()));
    }
}
