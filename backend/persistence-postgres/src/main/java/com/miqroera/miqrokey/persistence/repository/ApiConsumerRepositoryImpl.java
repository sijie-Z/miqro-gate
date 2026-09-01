package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
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
public class ApiConsumerRepositoryImpl implements ApiConsumerRepository {

    private static final RowMapper<ApiConsumer> ROW_MAPPER = (rs, rowNum) -> new ApiConsumer((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("name"), rs.getBytes("key_digest"),
            rs.getString("key_prefix"), rs.getString("status"), rs.getString("jwt_public_key_pem"),
            rs.getString("jwt_key_fingerprint"),
            rs.getTimestamp("jwt_key_set_at") != null ? rs.getTimestamp("jwt_key_set_at").toInstant() : null,
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ApiConsumerRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ApiConsumer insert(ApiConsumer consumer) {
        jdbc.update("""
                INSERT INTO api_consumers
                    (id, tenant_id, name, key_digest, key_prefix, status, jwt_public_key_pem,
                     jwt_key_fingerprint, jwt_key_set_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :name, :keyDigest, :keyPrefix, :status, :jwtPem, :jwtFingerprint,
                        :jwtSetAt, 0, now(), now())
                """, new MapSqlParameterSource("id", consumer.id()).addValue("tenantId", consumer.tenantId())
                .addValue("name", consumer.name()).addValue("keyDigest", consumer.keyDigest())
                .addValue("keyPrefix", consumer.keyPrefix()).addValue("status", consumer.status())
                .addValue("jwtPem", consumer.jwtPublicKeyPem()).addValue("jwtFingerprint", consumer.jwtKeyFingerprint())
                .addValue("jwtSetAt",
                        consumer.jwtKeySetAt() != null ? java.sql.Timestamp.from(consumer.jwtKeySetAt()) : null));
        return consumer;
    }

    @Override
    public List<ApiConsumer> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM api_consumers WHERE tenant_id = :tenantId ORDER BY created_at",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    public Optional<ApiConsumer> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT * FROM api_consumers WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ApiConsumer> findByKeyDigest(byte[] keyDigest) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM api_consumers WHERE key_digest = :keyDigest AND status = 'ACTIVE'",
                    new MapSqlParameterSource("keyDigest", keyDigest), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ApiConsumer> findByName(String name) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM api_consumers WHERE name = :name",
                    new MapSqlParameterSource("name", name), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public ApiConsumer update(ApiConsumer consumer) {
        jdbc.update("""
                UPDATE api_consumers
                SET name = :name, status = :status, jwt_public_key_pem = :jwtPem,
                    jwt_key_fingerprint = :jwtFingerprint, jwt_key_set_at = :jwtSetAt,
                    version = :version, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :oldVersion
                """, new MapSqlParameterSource("name", consumer.name()).addValue("status", consumer.status())
                .addValue("jwtPem", consumer.jwtPublicKeyPem()).addValue("jwtFingerprint", consumer.jwtKeyFingerprint())
                .addValue("jwtSetAt",
                        consumer.jwtKeySetAt() != null ? java.sql.Timestamp.from(consumer.jwtKeySetAt()) : null)
                .addValue("version", consumer.version() + 1).addValue("oldVersion", consumer.version())
                .addValue("id", consumer.id()).addValue("tenantId", consumer.tenantId()));
        return new ApiConsumer(consumer.id(), consumer.tenantId(), consumer.name(), consumer.keyDigest(),
                consumer.keyPrefix(), consumer.status(), consumer.jwtPublicKeyPem(), consumer.jwtKeyFingerprint(),
                consumer.jwtKeySetAt(), consumer.version() + 1, consumer.createdAt(), java.time.Instant.now());
    }
}
