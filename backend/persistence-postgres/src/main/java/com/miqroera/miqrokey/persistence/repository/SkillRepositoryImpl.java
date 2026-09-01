package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.Skill;
import com.miqroera.miqrokey.domain.model.SkillAccess;
import com.miqroera.miqrokey.domain.repository.SkillRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class SkillRepositoryImpl implements SkillRepository {

    private static final RowMapper<Skill> ROW_MAPPER = (rs, rowNum) -> {
        java.sql.Array array = rs.getArray("tags");
        List<String> tags = array != null ? Arrays.asList((String[]) array.getArray()) : List.of();
        return new Skill((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("version"), rs.getString("author"), rs.getString("license"),
                tags, rs.getBytes("content_zip"), rs.getString("content_sha256"), rs.getLong("content_bytes"),
                rs.getString("status"), (UUID) rs.getObject("created_by"), rs.getLong("row_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    };

    private static final RowMapper<SkillAccess> ACCESS_ROW_MAPPER = (rs, rowNum) -> new SkillAccess(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("skill_id"),
            rs.getString("scope_type"), (UUID) rs.getObject("scope_id"), rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public SkillRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Skill insert(Skill skill) {
        jdbc.update("""
                INSERT INTO skills
                    (id, tenant_id, name, description, version, author, license, tags, content_zip,
                     content_sha256, content_bytes, status, created_by, row_version, created_at, updated_at)
                VALUES (:id, :tenantId, :name, :description, :version, :author, :license, :tags, :contentZip,
                        :sha256, :bytes, 'ACTIVE', :createdBy, 0, now(), now())
                """, params(skill));
        return skill;
    }

    @Override
    @Transactional
    public Skill upsert(Skill skill) {
        jdbc.update("""
                INSERT INTO skills
                    (id, tenant_id, name, description, version, author, license, tags, content_zip,
                     content_sha256, content_bytes, status, created_by, row_version, created_at, updated_at)
                VALUES (:id, :tenantId, :name, :description, :version, :author, :license, :tags, :contentZip,
                        :sha256, :bytes, 'ACTIVE', :createdBy, 0, now(), now())
                ON CONFLICT (tenant_id, name) DO UPDATE
                    SET description = EXCLUDED.description, version = EXCLUDED.version,
                        author = EXCLUDED.author, license = EXCLUDED.license, tags = EXCLUDED.tags,
                        content_zip = EXCLUDED.content_zip, content_sha256 = EXCLUDED.content_sha256,
                        content_bytes = EXCLUDED.content_bytes, status = 'ACTIVE', created_by = EXCLUDED.created_by,
                        row_version = skills.row_version + 1, updated_at = now()
                """, params(skill));
        return findByName(skill.tenantId(), skill.name()).orElse(skill);
    }

    @Override
    public Optional<Skill> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional
                    .ofNullable(jdbc.queryForObject("SELECT * FROM skills WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Skill> findByName(UUID tenantId, String name) {
        try {
            return Optional
                    .ofNullable(jdbc.queryForObject("SELECT * FROM skills WHERE tenant_id = :tenantId AND name = :name",
                            new MapSqlParameterSource("tenantId", tenantId).addValue("name", name), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Skill> findAllActive(UUID tenantId) {
        return jdbc.query("SELECT * FROM skills WHERE tenant_id = :tenantId AND status = 'ACTIVE' ORDER BY name",
                new MapSqlParameterSource("tenantId", tenantId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public Skill archive(UUID tenantId, UUID skillId) {
        jdbc.update("""
                UPDATE skills SET status = 'ARCHIVED', row_version = row_version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource("id", skillId).addValue("tenantId", tenantId));
        return findByIdAndTenantId(skillId, tenantId)
                .orElseThrow(() -> new IllegalStateException("skill vanished during archive"));
    }

    @Override
    @Transactional
    public boolean delete(UUID tenantId, UUID skillId) {
        return jdbc.update("DELETE FROM skills WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", skillId).addValue("tenantId", tenantId)) > 0;
    }

    // ---- download grants ----

    @Override
    @Transactional
    public void insertAccess(SkillAccess access) {
        jdbc.update("""
                INSERT INTO skill_access (id, tenant_id, skill_id, scope_type, scope_id)
                VALUES (:id, :tenantId, :skillId, :scopeType, :scopeId)
                ON CONFLICT (skill_id, scope_type, scope_id) DO NOTHING
                """,
                new MapSqlParameterSource("id", access.id()).addValue("tenantId", access.tenantId())
                        .addValue("skillId", access.skillId()).addValue("scopeType", access.scopeType())
                        .addValue("scopeId", access.scopeId()));
    }

    @Override
    @Transactional
    public void deleteAccess(UUID tenantId, UUID skillId, String scopeType, UUID scopeId) {
        jdbc.update("""
                DELETE FROM skill_access
                WHERE tenant_id = :tenantId AND skill_id = :skillId AND scope_type = :scopeType AND scope_id = :scopeId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId)
                .addValue("scopeType", scopeType).addValue("scopeId", scopeId));
    }

    @Override
    public List<SkillAccess> findAccess(UUID tenantId, UUID skillId) {
        return jdbc.query("SELECT * FROM skill_access WHERE tenant_id = :tenantId AND skill_id = :skillId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId), ACCESS_ROW_MAPPER);
    }

    @Override
    public boolean canDownload(UUID tenantId, UUID skillId, UUID userId, boolean admin) {
        if (admin) {
            return true;
        }
        Long granted = jdbc.queryForObject("""
                SELECT COUNT(*) FROM skill_access a
                WHERE a.tenant_id = :tenantId AND a.skill_id = :skillId
                  AND (
                      (a.scope_type = 'TEAM' AND EXISTS (
                          SELECT 1 FROM team_memberships tm
                          WHERE tm.tenant_id = a.tenant_id AND tm.team_id = a.scope_id AND tm.user_id = :userId))
                      OR (a.scope_type = 'PROJECT' AND EXISTS (
                          SELECT 1 FROM project_memberships pm
                          WHERE pm.tenant_id = a.tenant_id AND pm.project_id = a.scope_id AND pm.user_id = :userId))
                  )
                """,
                new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId).addValue("userId", userId),
                Long.class);
        if (granted != null && granted > 0) {
            return true;
        }
        // No access rows at all -> public skill.
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_access WHERE tenant_id = :tenantId AND skill_id = :skillId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId), Long.class);
        return rows == null || rows == 0;
    }

    private static MapSqlParameterSource params(Skill s) {
        return new MapSqlParameterSource("id", s.id()).addValue("tenantId", s.tenantId()).addValue("name", s.name())
                .addValue("description", s.description()).addValue("version", s.version())
                .addValue("author", s.author()).addValue("license", s.license())
                .addValue("tags", s.tags().toArray(new String[0])).addValue("contentZip", s.contentZip())
                .addValue("sha256", s.contentSha256()).addValue("bytes", s.contentBytes())
                .addValue("createdBy", s.createdBy()).addValue("rowVersion", s.rowVersion());
    }
}
