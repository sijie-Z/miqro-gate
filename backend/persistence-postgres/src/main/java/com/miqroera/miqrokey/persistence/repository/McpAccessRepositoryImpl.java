package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.McpAccessGrant;
import com.miqroera.miqrokey.domain.model.McpAclMode;
import com.miqroera.miqrokey.domain.model.McpServiceAccess;
import com.miqroera.miqrokey.domain.repository.McpAccessRepository;
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
public class McpAccessRepositoryImpl implements McpAccessRepository {

    private static final String ACCESS_COLS = "id, tenant_id, mcp_service_id, mode, created_by, version, created_at,"
            + " updated_at";
    private static final String GRANT_COLS = "id, tenant_id, service_access_id, tool_id, consumer_id, mode,"
            + " created_by, version, created_at, updated_at";

    private static final RowMapper<McpServiceAccess> ACCESS_MAPPER = (rs, rowNum) -> new McpServiceAccess(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("mcp_service_id"),
            McpAclMode.valueOf(rs.getString("mode")), (UUID) rs.getObject("created_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<McpAccessGrant> GRANT_MAPPER = (rs, rowNum) -> new McpAccessGrant(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("service_access_id"),
            (UUID) rs.getObject("tool_id"), (UUID) rs.getObject("consumer_id"),
            McpAclMode.valueOf(rs.getString("mode")), (UUID) rs.getObject("created_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public McpAccessRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public McpServiceAccess upsertService(McpServiceAccess access) {
        return jdbc.queryForObject("""
                INSERT INTO mcp_service_access (id, tenant_id, mcp_service_id, mode, created_by, version,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :mcpServiceId, :mode, :createdBy, 0, :createdAt, :updatedAt)
                ON CONFLICT (mcp_service_id) DO UPDATE SET
                    mode = EXCLUDED.mode,
                    version = mcp_service_access.version + 1,
                    updated_at = EXCLUDED.updated_at
                RETURNING
                """ + ACCESS_COLS,
                new MapSqlParameterSource().addValue("id", access.id()).addValue("tenantId", access.tenantId())
                        .addValue("mcpServiceId", access.mcpServiceId()).addValue("mode", access.mode().name())
                        .addValue("createdBy", access.createdBy()).addValue("createdAt", toTs(access.createdAt()))
                        .addValue("updatedAt", toTs(access.updatedAt())),
                ACCESS_MAPPER);
    }

    @Override
    public Optional<McpServiceAccess> findService(UUID tenantId, UUID mcpServiceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + ACCESS_COLS + " FROM mcp_service_access"
                            + " WHERE tenant_id = :tenantId AND mcp_service_id = :mcpServiceId",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("mcpServiceId", mcpServiceId),
                    ACCESS_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<McpAccessGrant> findGrants(UUID tenantId, UUID serviceAccessId) {
        return jdbc.query("SELECT " + GRANT_COLS + " FROM mcp_access_grants"
                + " WHERE tenant_id = :tenantId AND service_access_id = :serviceAccessId ORDER BY tool_id NULLS FIRST",
                new MapSqlParameterSource("tenantId", tenantId).addValue("serviceAccessId", serviceAccessId),
                GRANT_MAPPER);
    }

    @Override
    @Transactional
    public void replaceGrants(UUID tenantId, UUID serviceAccessId, UUID toolId, List<McpAccessGrant> grants) {
        clearGrants(tenantId, serviceAccessId, toolId);
        Instant now = Instant.now();
        for (McpAccessGrant grant : grants) {
            jdbc.update("""
                    INSERT INTO mcp_access_grants
                        (id, tenant_id, service_access_id, tool_id, consumer_id, mode, created_by, version,
                         created_at, updated_at)
                    VALUES (:id, :tenantId, :serviceAccessId, :toolId, :consumerId, :mode, :createdBy, 0, :createdAt,
                            :updatedAt)
                    """,
                    new MapSqlParameterSource("id", grant.id()).addValue("tenantId", tenantId)
                            .addValue("serviceAccessId", serviceAccessId).addValue("toolId", toolId)
                            .addValue("consumerId", grant.consumerId()).addValue("mode", grant.mode().name())
                            .addValue("createdBy", grant.createdBy()).addValue("createdAt", toTs(now))
                            .addValue("updatedAt", toTs(now)));
        }
    }

    @Override
    @Transactional
    public void clearGrants(UUID tenantId, UUID serviceAccessId, UUID toolId) {
        var params = new MapSqlParameterSource("tenantId", tenantId).addValue("serviceAccessId", serviceAccessId)
                .addValue("toolId", toolId);
        // Explicit cast: PostgreSQL cannot infer a type for a null parameter
        // that appears in both "? IS NULL" and the equality comparison.
        jdbc.update("""
                DELETE FROM mcp_access_grants
                WHERE tenant_id = :tenantId AND service_access_id = :serviceAccessId
                  AND ((:toolId::uuid IS NULL AND tool_id IS NULL) OR tool_id = :toolId::uuid)
                """, params);
    }

    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
