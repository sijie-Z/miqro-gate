package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            capabilities(rs));

    /**
     * Reads the nullable jsonb scope column as a capability list (null = full).
     * Codes are application-validated to a fixed word set (no quotes/commas), so a
     * character-level parse is safe and keeps the row mapper static.
     */
    private static List<String> capabilities(java.sql.ResultSet rs) throws java.sql.SQLException {
        String raw = rs.getString("capabilities");
        if (raw == null) {
            return null;
        }
        String inner = raw.trim();
        if (inner.length() < 2 || !inner.startsWith("[")) {
            throw new IllegalStateException("Unreadable api_consumers.capabilities json for row");
        }
        inner = inner.substring(1, inner.length() - 1);
        if (inner.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String part : inner.split(",")) {
            String code = part.trim();
            if (code.length() >= 2 && code.startsWith("\"") && code.endsWith("\"")) {
                codes.add(code.substring(1, code.length() - 1));
            } else {
                throw new IllegalStateException("Unreadable api_consumers.capabilities json for row");
            }
        }
        return codes;
    }

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
                     jwt_key_fingerprint, jwt_key_set_at, version, created_at, updated_at, capabilities)
                VALUES (:id, :tenantId, :name, :keyDigest, :keyPrefix, :status, :jwtPem, :jwtFingerprint,
                        :jwtSetAt, 0, now(), now(), :capabilities::jsonb)
                """, new MapSqlParameterSource("id", consumer.id()).addValue("tenantId", consumer.tenantId())
                .addValue("name", consumer.name()).addValue("keyDigest", consumer.keyDigest())
                .addValue("keyPrefix", consumer.keyPrefix()).addValue("status", consumer.status())
                .addValue("jwtPem", consumer.jwtPublicKeyPem()).addValue("jwtFingerprint", consumer.jwtKeyFingerprint())
                .addValue("jwtSetAt",
                        consumer.jwtKeySetAt() != null ? java.sql.Timestamp.from(consumer.jwtKeySetAt()) : null)
                .addValue("capabilities", scopeJson(consumer.capabilities())));
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
                consumer.jwtKeySetAt(), consumer.version() + 1, consumer.createdAt(), java.time.Instant.now(),
                consumer.capabilities());
    }

    @Override
    @Transactional
    public ApiConsumer updateCapabilities(UUID id, UUID tenantId, List<String> capabilities) {
        jdbc.update("""
                UPDATE api_consumers
                SET capabilities = :capabilities::jsonb, version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("capabilities",
                scopeJson(capabilities)));
        return findByIdAndTenantId(id, tenantId).orElseThrow();
    }

    private static String scopeJson(List<String> capabilities) {
        if (capabilities == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < capabilities.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(capabilities.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
}
