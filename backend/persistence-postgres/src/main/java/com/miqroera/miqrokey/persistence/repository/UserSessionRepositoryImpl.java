package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.UserSession;
import com.miqroera.miqrokey.domain.repository.UserSessionRepository;
import org.springframework.dao.EmptyResultDataAccessException;
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
public class UserSessionRepositoryImpl implements UserSessionRepository {

    private static final RowMapper<UserSession> ROW_MAPPER = (rs, rowNum) -> new UserSession((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("user_id"), rs.getBytes("token_digest"),
            rs.getBytes("csrf_digest"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null);

    private final NamedParameterJdbcTemplate jdbc;

    public UserSessionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserSession> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM user_sessions WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<UserSession> findByTokenDigest(byte[] tokenDigest) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM user_sessions WHERE token_digest = :digest",
                    new MapSqlParameterSource("digest", tokenDigest), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<UserSession> findActiveByUserId(UUID userId, Instant now) {
        return jdbc.query(
                "SELECT * FROM user_sessions WHERE user_id = :userId AND revoked_at IS NULL AND expires_at > :now ORDER BY created_at DESC",
                new MapSqlParameterSource().addValue("userId", userId).addValue("now", Timestamp.from(now)),
                ROW_MAPPER);
    }

    @Override
    @Transactional
    public UserSession insert(UserSession session) {
        jdbc.update("""
                INSERT INTO user_sessions (id, tenant_id, user_id, token_digest, csrf_digest,
                    created_at, last_seen_at, expires_at, revoked_at)
                VALUES (:id, :tenantId, :userId, :tokenDigest, :csrfDigest,
                    :createdAt, :lastSeenAt, :expiresAt, :revokedAt)
                """, toParams(session));
        return session;
    }

    @Override
    @Transactional
    public void touch(UUID id, Instant lastSeenAt) {
        jdbc.update("UPDATE user_sessions SET last_seen_at = :lastSeenAt WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("lastSeenAt", Timestamp.from(lastSeenAt)));
    }

    @Override
    @Transactional
    public void revoke(UUID id, Instant revokedAt) {
        jdbc.update("UPDATE user_sessions SET revoked_at = :revokedAt WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("revokedAt", Timestamp.from(revokedAt)));
    }

    @Override
    @Transactional
    public void revokeAllByUserId(UUID userId, Instant revokedAt) {
        jdbc.update("UPDATE user_sessions SET revoked_at = :revokedAt WHERE user_id = :userId AND revoked_at IS NULL",
                new MapSqlParameterSource().addValue("userId", userId).addValue("revokedAt",
                        Timestamp.from(revokedAt)));
    }

    @Override
    @Transactional
    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM user_sessions WHERE expires_at <= :now",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    private MapSqlParameterSource toParams(UserSession s) {
        return new MapSqlParameterSource().addValue("id", s.id()).addValue("tenantId", s.tenantId())
                .addValue("userId", s.userId()).addValue("tokenDigest", s.tokenDigest())
                .addValue("csrfDigest", s.csrfDigest()).addValue("createdAt", Timestamp.from(s.createdAt()))
                .addValue("lastSeenAt", Timestamp.from(s.lastSeenAt()))
                .addValue("expiresAt", Timestamp.from(s.expiresAt()))
                .addValue("revokedAt", s.revokedAt() != null ? Timestamp.from(s.revokedAt()) : null);
    }
}
