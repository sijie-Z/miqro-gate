package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.McpToolRevision;
import com.miqroera.miqrokey.domain.repository.McpToolRevisionRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class McpToolRevisionRepositoryImpl implements McpToolRevisionRepository {

    private static final RowMapper<McpToolRevision> ROW_MAPPER = (rs, rowNum) -> new McpToolRevision(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("tool_id"),
            rs.getLong("revision"), rs.getString("description"), rs.getString("method"), rs.getString("path"),
            (UUID) rs.getObject("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("activated_at") != null ? rs.getTimestamp("activated_at").toInstant() : null);

    private final NamedParameterJdbcTemplate jdbc;

    public McpToolRevisionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public McpToolRevision insert(McpToolRevision revision) {
        jdbc.update("""
                INSERT INTO mcp_tool_revisions
                    (id, tenant_id, tool_id, revision, description, method, path, created_by, created_at,
                     activated_at)
                VALUES (:id, :tenantId, :toolId, :revision, :description, :method, :path, :createdBy, :createdAt,
                        :activatedAt)
                """, new MapSqlParameterSource("id", revision.id()).addValue("tenantId", revision.tenantId())
                .addValue("toolId", revision.toolId()).addValue("revision", revision.revision())
                .addValue("description", revision.description()).addValue("method", revision.method())
                .addValue("path", revision.path()).addValue("createdBy", revision.createdBy())
                .addValue("createdAt", java.sql.Timestamp.from(revision.createdAt())).addValue("activatedAt",
                        revision.activatedAt() != null ? java.sql.Timestamp.from(revision.activatedAt()) : null));
        return revision;
    }

    @Override
    public long maxRevision(UUID tenantId, UUID toolId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM mcp_tool_revisions WHERE tenant_id = :tenantId"
                        + " AND tool_id = :toolId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("toolId", toolId), Long.class);
        return max == null ? 0 : max;
    }

    @Override
    public Optional<McpToolRevision> findByToolAndRevision(UUID tenantId, UUID toolId, long revision) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM mcp_tool_revisions
                    WHERE tenant_id = :tenantId AND tool_id = :toolId AND revision = :revision
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("toolId", toolId).addValue("revision",
                    revision), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<McpToolRevision> findActive(UUID tenantId, UUID toolId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM mcp_tool_revisions
                    WHERE tenant_id = :tenantId AND tool_id = :toolId AND activated_at IS NOT NULL
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("toolId", toolId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<McpToolRevision> listByTool(UUID tenantId, UUID toolId, int limit) {
        return jdbc.query("""
                SELECT * FROM mcp_tool_revisions
                WHERE tenant_id = :tenantId AND tool_id = :toolId
                ORDER BY revision DESC
                LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("toolId", toolId).addValue("limit",
                Math.min(limit, 50)), ROW_MAPPER);
    }

    @Override
    @Transactional
    public int deactivateOthers(UUID tenantId, UUID toolId, long keepRevision) {
        return jdbc.update("""
                UPDATE mcp_tool_revisions SET activated_at = NULL
                WHERE tenant_id = :tenantId AND tool_id = :toolId AND revision <> :keepRevision
                  AND activated_at IS NOT NULL
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("toolId", toolId).addValue("keepRevision",
                keepRevision));
    }

    @Override
    @Transactional
    public int activate(UUID tenantId, UUID toolId, long revision, Instant activatedAt) {
        return jdbc.update("""
                UPDATE mcp_tool_revisions SET activated_at = :activatedAt
                WHERE tenant_id = :tenantId AND tool_id = :toolId AND revision = :revision
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("toolId", toolId)
                .addValue("revision", revision).addValue("activatedAt", java.sql.Timestamp.from(activatedAt)));
    }

    @Override
    @Transactional
    public int mirrorToTool(UUID tenantId, UUID toolId, String description, String method, String path) {
        return jdbc.update("""
                UPDATE mcp_tools
                SET description = :description, method = :method, path = :path, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :toolId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("toolId", toolId)
                .addValue("description", description).addValue("method", method).addValue("path", path));
    }
}
