package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.ConfigEntry;
import com.miqroera.miqrokey.domain.repository.ConfigEntryRepository;
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
public class ConfigEntryRepositoryImpl implements ConfigEntryRepository {

    private static final RowMapper<ConfigEntry> ROW_MAPPER = (rs, rowNum) -> new ConfigEntry((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), rs.getString("group_name"), rs.getString("key"), rs.getString("value"),
            rs.getString("description"), (UUID) rs.getObject("updated_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ConfigEntryRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ConfigEntry upsert(ConfigEntry entry) {
        jdbc.update("""
                INSERT INTO config_entries
                    (id, tenant_id, group_name, key, value, description, updated_by, version, created_at, updated_at)
                VALUES (:id, :tenantId, :groupName, :key, :value, :description, :updatedBy, 0, now(), now())
                ON CONFLICT (tenant_id, group_name, key) DO UPDATE
                    SET value = EXCLUDED.value, description = EXCLUDED.description,
                        updated_by = EXCLUDED.updated_by, version = config_entries.version + 1, updated_at = now()
                """, new MapSqlParameterSource("id", entry.id()).addValue("tenantId", entry.tenantId())
                .addValue("groupName", entry.groupName()).addValue("key", entry.key()).addValue("value", entry.value())
                .addValue("description", entry.description()).addValue("updatedBy", entry.updatedBy()));
        return findByGroupAndKey(entry.tenantId(), entry.groupName(), entry.key()).orElse(entry);
    }

    @Override
    public Optional<ConfigEntry> findByGroupAndKey(UUID tenantId, String groupName, String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM config_entries WHERE tenant_id = :tenantId AND group_name = :groupName AND key = :key",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("groupName", groupName).addValue("key",
                            key),
                    ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ConfigEntry> findAllByTenantId(UUID tenantId) {
        return jdbc.query("SELECT * FROM config_entries WHERE tenant_id = :tenantId ORDER BY group_name, key",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    public List<ConfigEntry> findAllByGroup(UUID tenantId, String groupName) {
        return jdbc.query(
                "SELECT * FROM config_entries WHERE tenant_id = :tenantId AND group_name = :groupName ORDER BY key",
                new MapSqlParameterSource("tenantId", tenantId).addValue("groupName", groupName), ROW_MAPPER);
    }

    @Override
    @Transactional
    public boolean delete(UUID tenantId, String groupName, String key) {
        return jdbc.update("""
                DELETE FROM config_entries WHERE tenant_id = :tenantId AND group_name = :groupName AND key = :key
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("groupName", groupName).addValue("key",
                key)) > 0;
    }
}
