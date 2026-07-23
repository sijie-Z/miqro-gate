package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UserRepository;
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
public class UserRepositoryImpl implements UserRepository {

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> new User((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("username"), rs.getString("display_name"),
            rs.getBytes("password_hash"), UserRole.valueOf(rs.getString("role")),
            UserStatus.valueOf(rs.getString("status")), rs.getBoolean("must_change_password"),
            rs.getInt("failed_login_count"),
            rs.getTimestamp("locked_until") != null ? rs.getTimestamp("locked_until").toInstant() : null,
            rs.getTimestamp("last_login_at") != null ? rs.getTimestamp("last_login_at").toInstant() : null,
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM users WHERE id = :id",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<User> findByTenantIdAndUsername(UUID tenantId, String username) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM users WHERE tenant_id = :tenantId AND lower(username) = lower(:username)",
                    new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("username", username),
                    ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<User> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM users WHERE tenant_id = :tenantId ORDER BY username",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    public List<User> findAllByTenantIdAndStatus(UUID tenantId, String status) {
        return jdbc.query("SELECT * FROM users WHERE tenant_id = :tenantId AND status = :status ORDER BY username",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("status", status), ROW_MAPPER);
    }

    @Override
    @Transactional
    public User insert(User user) {
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash,
                    role, status, must_change_password, failed_login_count, locked_until,
                    last_login_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :username, :displayName, :passwordHash,
                    :role, :status, :mustChangePassword, :failedLoginCount, :lockedUntil,
                    :lastLoginAt, :version, :createdAt, :updatedAt)
                """, toParams(user));
        return user;
    }

    @Override
    @Transactional
    public User update(User user) {
        long expectedVersion = user.version() - 1;
        var params = toParams(user).addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update("""
                UPDATE users SET display_name = :displayName, password_hash = :passwordHash,
                    role = :role, status = :status, must_change_password = :mustChangePassword,
                    failed_login_count = :failedLoginCount, locked_until = :lockedUntil,
                    last_login_at = :lastLoginAt, version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, params);
        if (rows != 1)
            throw new IllegalStateException("Optimistic lock failure: user " + user.id());
        return user;
    }

    @Override
    public boolean existsByTenantIdAndUsername(UUID tenantId, String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = :tenantId AND lower(username) = lower(:username)",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("username", username),
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public int countByTenantId(UUID tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count != null ? count : 0;
    }

    @Override
    @Transactional
    public void lockTenantForBootstrap(UUID tenantId) {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", tenantId), UUID.class);
    }

    @Override
    public Optional<User> findByIdForUpdate(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM users WHERE id = :id FOR UPDATE",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private MapSqlParameterSource toParams(User u) {
        return new MapSqlParameterSource().addValue("id", u.id()).addValue("tenantId", u.tenantId())
                .addValue("username", u.username()).addValue("displayName", u.displayName())
                .addValue("passwordHash", u.passwordHash()).addValue("role", u.role().name())
                .addValue("status", u.status().name()).addValue("mustChangePassword", u.mustChangePassword())
                .addValue("failedLoginCount", u.failedLoginCount())
                .addValue("lockedUntil", u.lockedUntil() != null ? Timestamp.from(u.lockedUntil()) : null)
                .addValue("lastLoginAt", u.lastLoginAt() != null ? Timestamp.from(u.lastLoginAt()) : null)
                .addValue("version", u.version()).addValue("createdAt", Timestamp.from(u.createdAt()))
                .addValue("updatedAt", Timestamp.from(u.updatedAt()));
    }
}
