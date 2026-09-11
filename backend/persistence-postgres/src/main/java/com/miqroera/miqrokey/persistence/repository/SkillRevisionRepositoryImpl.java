package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.SkillRevision;
import com.miqroera.miqrokey.domain.repository.SkillRevisionRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class SkillRevisionRepositoryImpl implements SkillRevisionRepository {

    private static final RowMapper<SkillRevision> ROW_MAPPER = (rs, rowNum) -> new SkillRevision(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("skill_id"),
            rs.getLong("revision"), rs.getString("version"), rs.getString("description"), rs.getString("author"),
            rs.getString("license"), Arrays.asList((String[]) rs.getArray("tags").getArray()),
            Arrays.asList((String[]) rs.getArray("examples").getArray()), rs.getBytes("content_zip"),
            rs.getString("content_sha256"), rs.getLong("content_bytes"), (UUID) rs.getObject("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("activated_at") != null ? rs.getTimestamp("activated_at").toInstant() : null);

    private final NamedParameterJdbcTemplate jdbc;

    public SkillRevisionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public SkillRevision insert(SkillRevision revision) {
        jdbc.update("""
                INSERT INTO skill_revisions
                    (id, tenant_id, skill_id, revision, version, description, author, license, tags, examples,
                     content_zip, content_sha256, content_bytes, created_by, created_at, activated_at)
                VALUES (:id, :tenantId, :skillId, :revision, :version, :description, :author, :license,
                        :tags, :examples, :contentZip, :sha256, :bytes, :createdBy, :createdAt, :activatedAt)
                """,
                new MapSqlParameterSource("id", revision.id()).addValue("tenantId", revision.tenantId())
                        .addValue("skillId", revision.skillId()).addValue("revision", revision.revision())
                        .addValue("version", revision.version()).addValue("description", revision.description())
                        .addValue("author", revision.author()).addValue("license", revision.license())
                        .addValue("tags", revision.tags().toArray(new String[0]))
                        .addValue("examples", revision.examples().toArray(new String[0]))
                        .addValue("contentZip", revision.contentZip()).addValue("sha256", revision.contentSha256())
                        .addValue("bytes", revision.contentBytes()).addValue("createdBy", revision.createdBy())
                        .addValue("createdAt", Timestamp.from(revision.createdAt())).addValue("activatedAt",
                                revision.activatedAt() != null ? Timestamp.from(revision.activatedAt()) : null));
        return revision;
    }

    @Override
    public long maxRevision(UUID tenantId, UUID skillId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM skill_revisions WHERE tenant_id = :tenantId"
                        + " AND skill_id = :skillId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId), Long.class);
        return max == null ? 0 : max;
    }

    @Override
    public Optional<SkillRevision> findBySkillAndRevision(UUID tenantId, UUID skillId, long revision) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM skill_revisions
                    WHERE tenant_id = :tenantId AND skill_id = :skillId AND revision = :revision
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId)
                    .addValue("revision", revision), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<SkillRevision> findActive(UUID tenantId, UUID skillId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM skill_revisions
                    WHERE tenant_id = :tenantId AND skill_id = :skillId AND activated_at IS NOT NULL
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<SkillRevision> listBySkill(UUID tenantId, UUID skillId, int limit) {
        return jdbc.query("""
                SELECT * FROM skill_revisions
                WHERE tenant_id = :tenantId AND skill_id = :skillId
                ORDER BY revision DESC
                LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId).addValue("limit",
                Math.min(limit, 50)), ROW_MAPPER);
    }

    @Override
    @Transactional
    public int deactivateOthers(UUID tenantId, UUID skillId, long keepRevision) {
        return jdbc.update("""
                UPDATE skill_revisions SET activated_at = NULL
                WHERE tenant_id = :tenantId AND skill_id = :skillId AND revision <> :keepRevision
                  AND activated_at IS NOT NULL
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId)
                .addValue("keepRevision", keepRevision));
    }

    @Override
    @Transactional
    public int activate(UUID tenantId, UUID skillId, long revision, Instant activatedAt) {
        return jdbc.update("""
                UPDATE skill_revisions SET activated_at = :activatedAt
                WHERE tenant_id = :tenantId AND skill_id = :skillId AND revision = :revision
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId)
                .addValue("revision", revision).addValue("activatedAt", Timestamp.from(activatedAt)));
    }

    @Override
    @Transactional
    public int mirrorToSkill(UUID tenantId, UUID skillId, String description, String author, String license,
            List<String> tags, List<String> examples, String version, byte[] contentZip, String contentSha256,
            long contentBytes) {
        return jdbc.update("""
                UPDATE skills
                SET description = :description, author = :author, license = :license,
                    tags = :tags, examples = :examples, version = :version,
                    content_zip = :contentZip, content_sha256 = :sha256, content_bytes = :bytes, status = 'ACTIVE',
                    row_version = row_version + 1, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :skillId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("skillId", skillId)
                .addValue("description", description).addValue("author", author).addValue("license", license)
                .addValue("tags", tags.toArray(new String[0])).addValue("examples", examples.toArray(new String[0]))
                .addValue("version", version).addValue("contentZip", contentZip).addValue("sha256", contentSha256)
                .addValue("bytes", contentBytes));
    }

}
