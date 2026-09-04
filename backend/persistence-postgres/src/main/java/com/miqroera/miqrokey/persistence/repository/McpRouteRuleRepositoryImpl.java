package com.miqroera.miqrokey.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.McpHeaderCondition;
import com.miqroera.miqrokey.domain.model.McpRouteRule;
import com.miqroera.miqrokey.domain.repository.McpRouteRuleRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code mcp_route_rule} (V28, F11) JDBC access. header_conditions is a jsonb
 * array of {@code {name, mode, value}}; rows are serialized with Jackson and
 * parsed back on reads.
 */
@Repository
@Transactional(readOnly = true)
public class McpRouteRuleRepositoryImpl implements McpRouteRuleRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public McpRouteRuleRepositoryImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private static final String COLUMNS = "id, tenant_id, mcp_service_id, name, description, priority, path_mode, "
            + "path_value, host_mode, host_value, methods, header_conditions::text AS header_conditions, status, "
            + "version, created_by, created_at, updated_at";

    @Override
    @Transactional
    public McpRouteRule insert(McpRouteRule rule) {
        jdbc.update("""
                INSERT INTO mcp_route_rule
                    (id, tenant_id, mcp_service_id, name, description, priority, path_mode, path_value,
                     host_mode, host_value, methods, header_conditions, status, version, created_by,
                     created_at, updated_at)
                VALUES (:id, :tenantId, :serviceId, :name, :description, :priority, :pathMode, :pathValue,
                        :hostMode, :hostValue, :methods, :headerConditions::jsonb, :status, 0, :createdBy,
                        now(), now())
                """, params(rule));
        return rule;
    }

    @Override
    public Optional<McpRouteRule> findByIdAndTenantId(UUID id, UUID tenantId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM mcp_route_rule WHERE id = :id AND tenant_id = :tenantId",
                    new MapSqlParameterSource("id", id).addValue("tenantId", tenantId), rowMapper));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<McpRouteRule> findAllByService(UUID tenantId, UUID mcpServiceId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM mcp_route_rule WHERE tenant_id = :tenantId AND mcp_service_id = :serviceId "
                        + "ORDER BY priority DESC, name",
                new MapSqlParameterSource("tenantId", tenantId).addValue("serviceId", mcpServiceId), rowMapper);
    }

    @Override
    @Transactional
    public McpRouteRule update(McpRouteRule rule, long expectedVersion) {
        MapSqlParameterSource p = params(rule);
        p.addValue("expectedVersion", expectedVersion);
        int rows = jdbc.update("""
                UPDATE mcp_route_rule
                SET name = :name, description = :description, priority = :priority,
                    path_mode = :pathMode, path_value = :pathValue, host_mode = :hostMode, host_value = :hostValue,
                    methods = :methods, header_conditions = :headerConditions::jsonb,
                    version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, p);
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: mcp route rule " + rule.id());
        }
        return findByIdAndTenantId(rule.id(), rule.tenantId()).orElseThrow();
    }

    @Override
    @Transactional
    public McpRouteRule updateStatus(UUID tenantId, UUID ruleId, String status, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mcp_route_rule SET status = :status, version = version + 1, updated_at = now()
                WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """, new MapSqlParameterSource("status", status).addValue("id", ruleId).addValue("tenantId", tenantId)
                .addValue("expectedVersion", expectedVersion));
        if (rows != 1) {
            throw new IllegalStateException("Optimistic lock failure: mcp route rule " + ruleId);
        }
        return findByIdAndTenantId(ruleId, tenantId).orElseThrow();
    }

    @Override
    @Transactional
    public void deleteById(UUID tenantId, UUID ruleId) {
        jdbc.update("DELETE FROM mcp_route_rule WHERE id = :id AND tenant_id = :tenantId",
                new MapSqlParameterSource("id", ruleId).addValue("tenantId", tenantId));
    }

    private MapSqlParameterSource params(McpRouteRule rule) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", rule.id()).addValue("tenantId", rule.tenantId())
                .addValue("serviceId", rule.mcpServiceId()).addValue("name", rule.name())
                .addValue("description", rule.description()).addValue("priority", rule.priority())
                .addValue("pathMode", rule.pathMode()).addValue("pathValue", rule.pathValue())
                .addValue("hostMode", rule.hostMode()).addValue("hostValue", rule.hostValue())
                .addValue("methods", rule.methods()).addValue("status", rule.status())
                .addValue("createdBy", rule.createdBy());
        try {
            p.addValue("headerConditions", objectMapper.writeValueAsString(rule.headerConditions()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize route header conditions", e);
        }
        return p;
    }

    private final RowMapper<McpRouteRule> rowMapper = (rs, rowNum) -> new McpRouteRule((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("mcp_service_id"), rs.getString("name"),
            rs.getString("description"), rs.getInt("priority"), rs.getString("path_mode"), rs.getString("path_value"),
            rs.getString("host_mode"), rs.getString("host_value"), rs.getString("methods"), readConditions(rs),
            rs.getString("status"), rs.getLong("version"), (UUID) rs.getObject("created_by"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private List<McpHeaderCondition> readConditions(ResultSet rs) throws SQLException {
        String json = rs.getString("header_conditions");
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var entries = objectMapper.readTree(json);
            List<McpHeaderCondition> conditions = new ArrayList<>();
            for (var entry : entries) {
                conditions.add(new McpHeaderCondition(entry.get("name").asText(), entry.get("mode").asText(),
                        entry.get("value").asText()));
            }
            return conditions;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Cannot parse route header conditions", e);
        }
    }
}
