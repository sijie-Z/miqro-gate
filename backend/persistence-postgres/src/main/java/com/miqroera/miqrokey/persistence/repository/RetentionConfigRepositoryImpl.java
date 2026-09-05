package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.RetentionConfig;
import com.miqroera.miqrokey.domain.repository.RetentionConfigRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Row access for {@code retention_config} (V31): per-tenant compliance switch.
 * Missing row == fully off.
 */
@Repository
@Transactional
public class RetentionConfigRepositoryImpl implements RetentionConfigRepository {

    private static final String COLS = "enabled, content_scope, key_version, version";

    private final NamedParameterJdbcTemplate jdbc;

    public RetentionConfigRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RetentionConfig> find(UUID tenantId) {
        return jdbc.query("""
                SELECT
                """ + COLS + """
                        FROM retention_config
                        WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty());
    }

    @Override
    public RetentionConfig upsert(UUID tenantId, RetentionConfig config, UUID updatedBy) {
        return jdbc.queryForObject("""
                INSERT INTO retention_config (tenant_id, enabled, content_scope, key_version, version, updated_by,
                    updated_at)
                VALUES (:tenantId, :enabled, :scope, :keyVersion, 0, :updatedBy, now())
                ON CONFLICT (tenant_id) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    content_scope = EXCLUDED.content_scope,
                    key_version = EXCLUDED.key_version,
                    version = retention_config.version + 1,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                RETURNING
                """ + COLS,
                new MapSqlParameterSource("tenantId", tenantId).addValue("enabled", config.enabled())
                        .addValue("scope", config.contentScope()).addValue("keyVersion", config.keyVersion())
                        .addValue("updatedBy", updatedBy),
                (rs, rowNum) -> map(rs));
    }

    private static RetentionConfig map(ResultSet rs) throws SQLException {
        return new RetentionConfig(rs.getBoolean("enabled"), rs.getString("content_scope"), rs.getString("key_version"),
                rs.getLong("version"));
    }
}
