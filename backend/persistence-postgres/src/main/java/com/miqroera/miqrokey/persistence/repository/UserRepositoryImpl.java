package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.repository.UserRepository;
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
            rs.getBytes("password_hash"), rs.getString("role"), rs.getString("status"),
            rs.getBoolean("must_change_password"), rs.getInt("failed_login_count"),
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
        var sql = "SELECT * FROM users WHERE id = :id";
        var params = new MapSqlParameterSource("id", id);
        var list = jdbc.query(sql, params, ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<User> findByTenantIdAndUsername(UUID tenantId, String username) {
        var sql = "SELECT * FROM users WHERE tenant_id = :tenantId AND lower(username) = lower(:username)";
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("username", username);
        var list = jdbc.query(sql, params, ROW_MAPPER);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<User> findAllByTenantId(UUID tenantId) {
        var sql = "SELECT * FROM users WHERE tenant_id = :tenantId ORDER BY username";
        return jdbc.query(sql, new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    public List<User> findAllByTenantIdAndStatus(UUID tenantId, String status) {
        var sql = "SELECT * FROM users WHERE tenant_id = :tenantId AND status = :status ORDER BY username";
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("status", status);
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    @Override
    @Transactional
    public User insert(User user) {
        var sql = """
                INSERT INTO users (id, tenant_id, username, display_name, password_hash,
                    role, status, must_change_password, failed_login_count, locked_until,
                    last_login_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :username, :displayName, :passwordHash,
                    :role, :status, :mustChangePassword, :failedLoginCount, :lockedUntil,
                    :lastLoginAt, :version, :createdAt, :updatedAt)
                """;
        jdbc.update(sql, toParams(user));
        return user;
    }

    @Override
    @Transactional
    public User update(User user) {
        var sql = """
                UPDATE users SET display_name = :displayName, password_hash = :passwordHash,
                    role = :role, status = :status, must_change_password = :mustChangePassword,
                    failed_login_count = :failedLoginCount, locked_until = :lockedUntil,
                    last_login_at = :lastLoginAt, version = :version, updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbc.update(sql, toParams(user));
        return user;
    }

    @Override
    public boolean existsByTenantIdAndUsername(UUID tenantId, String username) {
        var sql = "SELECT COUNT(*) FROM users WHERE tenant_id = :tenantId AND lower(username) = lower(:username)";
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("username", username);
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count != null && count > 0;
    }

    @Override
    public int countByTenantId(UUID tenantId) {
        var sql = "SELECT COUNT(*) FROM users WHERE tenant_id = :tenantId";
        Integer count = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count != null ? count : 0;
    }

    private MapSqlParameterSource toParams(User u) {
        return new MapSqlParameterSource().addValue("id", u.id()).addValue("tenantId", u.tenantId())
                .addValue("username", u.username()).addValue("displayName", u.displayName())
                .addValue("passwordHash", u.passwordHash()).addValue("role", u.role()).addValue("status", u.status())
                .addValue("mustChangePassword", u.mustChangePassword())
                .addValue("failedLoginCount", u.failedLoginCount())
                .addValue("lockedUntil", u.lockedUntil() != null ? Timestamp.from(u.lockedUntil()) : null)
                .addValue("lastLoginAt", u.lastLoginAt() != null ? Timestamp.from(u.lastLoginAt()) : null)
                .addValue("version", u.version()).addValue("createdAt", Timestamp.from(u.createdAt()))
                .addValue("updatedAt", Timestamp.from(u.updatedAt()));
    }
}
