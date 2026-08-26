package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.usage.ExportFormat;
import com.miqroera.miqrokey.domain.usage.ExportStatus;
import com.miqroera.miqrokey.domain.usage.ExportTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Async raw-usage export tasks (G4.4, {@code export_tasks} V11): create returns
 * the task immediately (202-style flow), a small bounded daemon executor
 * renders the window into CSV or JSONL (counts and metadata only — never
 * prompts, code, secrets or virtual-key plaintext), gzips it and stores the
 * artifact with its SHA-256. Downloads are served from the stored bytes until
 * the task expires.
 */
@Service
public class ExportTaskService {

    private static final Logger LOG = LoggerFactory.getLogger(ExportTaskService.class);

    private static final Duration MAX_WINDOW = Duration.ofDays(93);
    private static final Duration DOWNLOAD_TTL = Duration.ofHours(24);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "usage-export");
        t.setDaemon(true);
        return t;
    });

    public ExportTaskService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Creates an export task and schedules its execution. */
    public ExportTask create(UUID tenantId, UUID adminId, ExportFormat format, Instant from, Instant to) {
        validateWindow(from, to);
        ExportTask task = new ExportTask(UUID.randomUUID(), tenantId, adminId, format, from, to, ExportStatus.PENDING,
                null, null, null, null, null, Instant.now(), null, null);
        jdbc.update("""
                INSERT INTO export_tasks
                    (id, tenant_id, created_by, format, period_from, period_to, status, created_at)
                VALUES (:id, :tenantId, :createdBy, :format, :periodFrom, :periodTo, 'PENDING', :createdAt)
                """,
                new MapSqlParameterSource("id", task.id()).addValue("tenantId", tenantId).addValue("createdBy", adminId)
                        .addValue("format", format.name()).addValue("periodFrom", java.sql.Timestamp.from(from))
                        .addValue("periodTo", java.sql.Timestamp.from(to))
                        .addValue("createdAt", java.sql.Timestamp.from(task.createdAt())));
        executor.execute(() -> run(task));
        return task;
    }

    /** Task metadata (never the artifact bytes). */
    public ExportTask status(UUID tenantId, UUID taskId) {
        return find(tenantId, taskId);
    }

    /**
     * The finished artifact for download; EXPIRED or unfinished tasks are rejected.
     */
    public ExportTask download(UUID tenantId, UUID taskId) {
        ExportTask task = find(tenantId, taskId);
        if (task.status() != ExportStatus.SUCCEEDED || task.expiresAt() == null
                || task.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "EXPORT_EXPIRED",
                    "The export is not available for download (unfinished or expired)");
        }
        return task;
    }

    /** Recent tasks for the admin UI (metadata only). */
    public List<ExportTask> recent(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT * FROM export_tasks WHERE tenant_id = :tenantId
                ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("limit", Math.min(limit, 50)),
                ROW_MAPPER);
    }

    // -------------------------------------------------------------------

    private void run(ExportTask task) {
        mark(task.id(), ExportStatus.RUNNING, null);
        try {
            List<Map<String, Object>> rows = readRows(task);
            byte[] gzip = render(task.format(), rows);
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gzip));
            jdbc.update("""
                    UPDATE export_tasks
                    SET status = 'SUCCEEDED', sha256 = :sha256, row_count = :rows, byte_count = :bytes,
                        file_bytes = :file, error_message = NULL, finished_at = :finishedAt,
                        expires_at = :expiresAt
                    WHERE id = :id
                    """,
                    new MapSqlParameterSource("sha256", sha256).addValue("rows", rows.size())
                            .addValue("bytes", gzip.length).addValue("file", gzip)
                            .addValue("finishedAt", java.sql.Timestamp.from(Instant.now()))
                            .addValue("expiresAt", java.sql.Timestamp.from(Instant.now().plus(DOWNLOAD_TTL)))
                            .addValue("id", task.id()));
        } catch (Exception e) {
            LOG.warn("Export task {} failed", task.id(), e);
            mark(task.id(), ExportStatus.FAILED, truncate(e.getMessage()));
        }
    }

    private void mark(UUID taskId, ExportStatus status, String error) {
        jdbc.update("""
                UPDATE export_tasks SET status = :status, error_message = :error, finished_at = :finishedAt
                WHERE id = :id
                """, new MapSqlParameterSource("status", status.name()).addValue("error", error)
                .addValue("finishedAt", java.sql.Timestamp.from(Instant.now())).addValue("id", taskId));
    }

    private List<Map<String, Object>> readRows(ExportTask task) {
        return jdbc.query("""
                SELECT occurred_at, model_id, cache_level,
                       COALESCE(input_tokens, prompt_tokens) AS input_tokens,
                       COALESCE(output_tokens, completion_tokens) AS output_tokens,
                       cache_read_input_tokens, cache_creation_input_tokens, total_tokens, latency_ms,
                       upstream_status_code, provider_request_id, gateway_request_id, is_complete, usage_missing,
                       virtual_key_id, project_id, provider_product_id, credential_id
                FROM usage_event
                WHERE tenant_id = :tenantId AND occurred_at >= :from AND occurred_at < :to
                ORDER BY occurred_at
                """,
                new MapSqlParameterSource("tenantId", task.tenantId())
                        .addValue("from", java.sql.Timestamp.from(task.periodFrom()))
                        .addValue("to", java.sql.Timestamp.from(task.periodTo())),
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
                    row.put("modelId", rs.getString("model_id"));
                    row.put("cacheLevel", rs.getString("cache_level"));
                    row.put("inputTokens", rs.getObject("input_tokens"));
                    row.put("outputTokens", rs.getObject("output_tokens"));
                    row.put("cacheReadInputTokens", rs.getObject("cache_read_input_tokens"));
                    row.put("cacheCreationInputTokens", rs.getObject("cache_creation_input_tokens"));
                    row.put("totalTokens", rs.getObject("total_tokens"));
                    row.put("latencyMs", rs.getObject("latency_ms"));
                    row.put("upstreamStatusCode", rs.getObject("upstream_status_code"));
                    row.put("providerRequestId", rs.getString("provider_request_id"));
                    row.put("gatewayRequestId", rs.getString("gateway_request_id"));
                    row.put("isComplete", rs.getBoolean("is_complete"));
                    row.put("usageMissing", rs.getBoolean("usage_missing"));
                    row.put("virtualKeyId", String.valueOf(rs.getObject("virtual_key_id")));
                    row.put("projectId", String.valueOf(rs.getObject("project_id")));
                    row.put("providerProductId", String.valueOf(rs.getObject("provider_product_id")));
                    row.put("credentialId",
                            rs.getObject("credential_id") != null
                                    ? String.valueOf(rs.getObject("credential_id"))
                                    : null);
                    return row;
                });
    }

    /** Renders rows into the requested format and gzips the result. */
    private byte[] render(ExportFormat format, List<Map<String, Object>> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            if (format == ExportFormat.CSV) {
                gzip.write("occurredAt,modelId,cacheLevel,inputTokens,outputTokens,cacheReadInputTokens,"
                        .getBytes(StandardCharsets.UTF_8));
                gzip.write("cacheCreationInputTokens,totalTokens,latencyMs,upstreamStatusCode,providerRequestId,"
                        .getBytes(StandardCharsets.UTF_8));
                gzip.write("gatewayRequestId,isComplete,usageMissing,virtualKeyId,projectId,providerProductId,"
                        .getBytes(StandardCharsets.UTF_8));
                gzip.write("credentialId\n".getBytes(StandardCharsets.UTF_8));
                for (Map<String, Object> row : rows) {
                    gzip.write(join(row.values()).getBytes(StandardCharsets.UTF_8));
                    gzip.write('\n');
                }
            } else {
                for (Map<String, Object> row : rows) {
                    gzip.write(objectMapper.writeValueAsBytes(row));
                    gzip.write('\n');
                }
            }
        }
        return out.toByteArray();
    }

    private static String join(Iterable<Object> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v == null ? "" : String.valueOf(v).replace(",", "\\,"));
        }
        return sb.toString();
    }

    private ExportTask find(UUID tenantId, UUID taskId) {
        List<ExportTask> found = jdbc.query("""
                SELECT * FROM export_tasks WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource("id", taskId).addValue("tenantId", tenantId), ROW_MAPPER);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "Export task not found or not visible");
        }
        return found.get(0);
    }

    private static void validateWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID", "from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_TOO_WIDE",
                    "The export window must be at most " + MAX_WINDOW.toDays() + " days");
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static final RowMapper<ExportTask> ROW_MAPPER = (rs, rowNum) -> new ExportTask((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("created_by"),
            ExportFormat.valueOf(rs.getString("format")), rs.getTimestamp("period_from").toInstant(),
            rs.getTimestamp("period_to").toInstant(), ExportStatus.valueOf(rs.getString("status")),
            rs.getString("sha256"), rs.getObject("row_count", Long.class), rs.getObject("byte_count", Long.class),
            rs.getBytes("file_bytes"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null,
            rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null);
}
