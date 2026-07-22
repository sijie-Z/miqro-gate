package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.CredentialStatus;
import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialRepository;
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
public class UpstreamCredentialRepositoryImpl implements UpstreamCredentialRepository {

    private static final RowMapper<UpstreamCredential> ROW_MAPPER = (rs, rowNum) -> new UpstreamCredential(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("subscription_id"),
            (UUID) rs.getObject("seat_id"), rs.getString("credential_name"), rs.getBytes("secret_fingerprint"),
            CredentialStatus.valueOf(rs.getString("status")), (UUID) rs.getObject("active_version_id"),
            rs.getTimestamp("last_validated_at") != null ? rs.getTimestamp("last_validated_at").toInstant() : null,
            rs.getString("last_validation_error"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;
    public UpstreamCredentialRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UpstreamCredential> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM upstream_credentials WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<UpstreamCredential> findAllBySubscriptionId(UUID subscriptionId) {
        return jdbc.query("SELECT * FROM upstream_credentials WHERE subscription_id = :subId",
                new MapSqlParameterSource("subId", subscriptionId), ROW_MAPPER);
    }

    @Override
    public List<UpstreamCredential> findAllBySubscriptionIdAndStatus(UUID subscriptionId, String status) {
        return jdbc.query("SELECT * FROM upstream_credentials WHERE subscription_id = :subId AND status = :status",
                new MapSqlParameterSource().addValue("subId", subscriptionId).addValue("status", status), ROW_MAPPER);
    }

    @Override
    @Transactional
    public UpstreamCredential insert(UpstreamCredential credential) {
        jdbc.update(
                "INSERT INTO upstream_credentials (id, tenant_id, subscription_id, seat_id, credential_name, secret_fingerprint, status, active_version_id, last_validated_at, last_validation_error, version, created_at, updated_at) VALUES (:id, :tenantId, :subscriptionId, :seatId, :credentialName, :secretFingerprint, :status, :activeVersionId, :lastValidatedAt, :lastValidationError, :version, :createdAt, :updatedAt)",
                toParams(credential));
        return credential;
    }

    @Override
    @Transactional
    public UpstreamCredential update(UpstreamCredential credential) {
        long expectedVersion = credential.version() - 1;
        var params = toParams(credential).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update(
                "UPDATE upstream_credentials SET credential_name = :credentialName, secret_fingerprint = :secretFingerprint, status = :status, active_version_id = :activeVersionId, last_validated_at = :lastValidatedAt, last_validation_error = :lastValidationError, version = version + 1, updated_at = :updatedAt WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion",
                params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: credential " + credential.id());
        return credential;
    }

    @Override
    public boolean existsById(UUID id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM upstream_credentials WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource toParams(UpstreamCredential c) {
        return new MapSqlParameterSource().addValue("id", c.id()).addValue("tenantId", c.tenantId())
                .addValue("subscriptionId", c.subscriptionId()).addValue("seatId", c.seatId())
                .addValue("credentialName", c.credentialName()).addValue("secretFingerprint", c.secretFingerprint())
                .addValue("status", c.status().name()).addValue("activeVersionId", c.activeVersionId())
                .addValue("lastValidatedAt", c.lastValidatedAt() != null ? Timestamp.from(c.lastValidatedAt()) : null)
                .addValue("lastValidationError", c.lastValidationError()).addValue("version", c.version())
                .addValue("createdAt", Timestamp.from(c.createdAt()))
                .addValue("updatedAt", Timestamp.from(c.updatedAt()));
    }
}
