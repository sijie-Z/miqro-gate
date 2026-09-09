package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AuditEventView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped reads over the append-only admin audit chain (G5.4): newest
 * first in causal order (chain_position DESC), optional filters (action /
 * resource target type / actor / time window) and {@code beforePosition}
 * cursor. Shared by the session admin endpoint and the open admin API
 * (ADR-0015) so both surfaces slice the same query with the same view.
 *
 * <p>
 * Compliance export (Tencent operation-records parity, raw 27): the same
 * filtered slice as a CSV document. Only non-chain metadata is exported —
 * hashes never serialize, and {@code changeSummary} content is guaranteed
 * secret-free by the {@code AuditService} contract. A hard row cap applies with
 * an explicit truncation flag: exports never silently drop rows.
 * </p>
 */
@Service
public class AuditEventReadService {

    /** Hard cap on rows returned per list request. */
    public static final int MAX_SIZE = 200;
    /** Hard cap on exported rows; a wider window must be narrowed by filters. */
    public static final int MAX_EXPORT_ROWS = 50_000;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditEventReadService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Optional audit query constraints; null fields mean "no constraint". */
    public record AuditFilters(String action, String targetType, UUID actorId, Instant from, Instant to) {
    }

    /** CSV export result: the document and whether it was truncated at the cap. */
    public record ExportResult(String csv, boolean truncated) {
    }

    public List<AuditEventView> list(UUID tenantId, int size, AuditFilters filters, Long beforePosition) {
        int limit = Math.min(Math.max(size, 1), MAX_SIZE);
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId).addValue("limit", limit);
        String where = whereClause(params, filters);
        if (beforePosition != null) {
            where += " AND chain_position < :beforePosition ";
            params.addValue("beforePosition", beforePosition);
        }
        return jdbc.query("""
                SELECT id, tenant_id, actor_id, action, target_type, target_id, change_summary, created_at,
                       chain_position
                FROM admin_audit_events
                WHERE %s
                ORDER BY chain_position DESC
                LIMIT :limit
                """.formatted(where), params, ROW_MAPPER);
    }

    /**
     * Compliance CSV export of the filtered slice (RFC 4180 quoting, UTF-8 BOM for
     * spreadsheet consumers). At most {@link #MAX_EXPORT_ROWS} rows: the caller
     * must narrow the window when the result reports truncation.
     */
    public ExportResult exportCsv(UUID tenantId, AuditFilters filters) {
        validateWindow(filters);
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
        String where = whereClause(params, filters);
        params.addValue("limit", MAX_EXPORT_ROWS + 1);
        List<AuditEventView> rows = jdbc.query("""
                SELECT id, tenant_id, actor_id, action, target_type, target_id, change_summary, created_at,
                       chain_position
                FROM admin_audit_events
                WHERE %s
                ORDER BY chain_position DESC
                LIMIT :limit
                """.formatted(where), params, ROW_MAPPER);
        boolean truncated = rows.size() > MAX_EXPORT_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_EXPORT_ROWS);
        }
        StringBuilder csv = new StringBuilder(rows.size() * 160 + 160);
        csv.append("\uFEFF"); // UTF-8 BOM so spreadsheet consumers detect the encoding
        csv.append("created_at,action,target_type,target_id,actor_id,change_summary,chain_position\n");
        for (AuditEventView row : rows) {
            csv.append(quote(row.createdAt().toString())).append(',');
            csv.append(quote(row.action())).append(',');
            csv.append(quote(row.targetType())).append(',');
            csv.append(quote(row.targetId() == null ? "" : row.targetId().toString())).append(',');
            csv.append(quote(row.actorId() == null ? "" : row.actorId().toString())).append(',');
            csv.append(quote(row.changeSummary())).append(',');
            csv.append(row.chainPosition()).append('\n');
        }
        return new ExportResult(csv.toString(), truncated);
    }

    private String whereClause(MapSqlParameterSource params, AuditFilters filters) {
        validateWindow(filters);
        StringBuilder where = new StringBuilder(" tenant_id = :tenantId ");
        if (filters.action() != null && !filters.action().isBlank()) {
            where.append(" AND action = :action ");
            params.addValue("action", filters.action());
        }
        if (filters.targetType() != null && !filters.targetType().isBlank()) {
            where.append(" AND target_type = :targetType ");
            params.addValue("targetType", filters.targetType());
        }
        if (filters.actorId() != null) {
            where.append(" AND actor_id = :actorId ");
            params.addValue("actorId", filters.actorId());
        }
        if (filters.from() != null) {
            where.append(" AND created_at >= :from ");
            params.addValue("from", java.sql.Timestamp.from(filters.from()));
        }
        if (filters.to() != null) {
            where.append(" AND created_at <= :to ");
            params.addValue("to", java.sql.Timestamp.from(filters.to()));
        }
        return where.toString();
    }

    private static void validateWindow(AuditFilters filters) {
        if (filters.from() != null && filters.to() != null && filters.from().isAfter(filters.to())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID", "from must not be after to");
        }
    }

    /**
     * RFC 4180: quote only when the field contains a separator, quote or line
     * break.
     */
    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final RowMapper<AuditEventView> ROW_MAPPER = (rs, rowNum) -> new AuditEventView(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("actor_id"), rs.getString("action"),
            rs.getString("target_type"), (UUID) rs.getObject("target_id"), rs.getString("change_summary"),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("chain_position"));
}
