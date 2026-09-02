package com.miqroera.miqrokey.persistence.repository;

import com.miqroera.miqrokey.domain.model.McpTool;
import com.miqroera.miqrokey.domain.repository.McpToolRepository;
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
public class McpToolRepositoryImpl implements McpToolRepository {

    private static final RowMapper<McpTool> ROW_MAPPER = (rs, rowNum) -> new McpTool((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("mcp_service_id"), rs.getString("tool_name"),
            rs.getString("description"), rs.getString("method"), rs.getString("path"), rs.getString("status"),
            rs.getLong("version"), (UUID) rs.getObject("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public McpToolRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public McpTool insert(McpTool tool) {
        jdbc.update("""
                INSERT INTO mcp_tools
                    (id, tenant_id, mcp_service_id, tool_name, description, method, path, status, version,
                     created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :serviceId, :toolName, :description, :method, :path, :status, 0, :createdBy,
                        now(), now())
                """,
                new MapSqlParameterSource("id", tool.id()).addValue("tenantId", tool.tenantId())
                        .addValue("serviceId", tool.mcpServiceId()).addValue("toolName", tool.toolName())
                        .addValue("description", tool.description()).addValue("method", tool.method())
                        .addValue("path", tool.path()).addValue("status", tool.status())
                        .addValue("createdBy", tool.createdBy()));
        return tool;
    }

    @Override
    public Optional<McpTool> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional
                    .ofNullable(jdbc.queryForObject("SELECT * FROM mcp_tools WHERE id = :id AND tenant_id = :tenantId",
                            new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), ROW_MAPPER));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<McpTool> findAllByService(UUID tenantId, UUID mcpServiceId) {
        return jdbc.query(
                "SELECT * FROM mcp_tools WHERE tenant_id = :tenantId AND mcp_service_id = :serviceId ORDER BY tool_name",
                new MapSqlParameterSource("tenantId", tenantId).addValue("serviceId", mcpServiceId), ROW_MAPPER);
    }

    @Override
    @Transactional
    public McpTool updateStatus(UUID tenantId, UUID toolId, String status, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mcp_tools SET status = :status, version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, new MapSqlParameterSource("status", status).addValue("id", toolId).addValue("tenantId", tenantId)
                .addValue("expectedVersion", expectedVersion));
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: mcp tool " + toolId);
        }
        return findByIdAndTenantId(toolId, tenantId).orElseThrow();
    }
}
