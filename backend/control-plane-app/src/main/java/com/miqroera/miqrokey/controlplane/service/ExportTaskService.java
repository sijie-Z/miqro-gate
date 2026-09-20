package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.dto.ExportTaskView;
import com.miqroera.miqrokey.domain.usage.ExportFormat;
import com.miqroera.miqrokey.domain.usage.ExportStatus;
import com.miqroera.miqrokey.domain.usage.ExportTask;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.persistence.repository.UsageAdjustmentSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final AuditService auditService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "usage-export");
        t.setDaemon(true);
        return t;
    });

    public ExportTaskService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, AuditService auditService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /**
     * Creates an export task and schedules its execution.
     *
     * <p>
     * The task extracts the tenant's raw usage rows into a downloadable artifact,
     * so creating one is audited ({@code EXPORT_CREATE}) against the requesting
     * admin — on the machine surface the {@link AuditContext#machine} marker also
     * names the key that asked for it.
     * </p>
     *
     * <p>
     * The row insert and its audit event share one transaction: a failed audit
     * write must not leave a committed {@code PENDING} task behind that no one ever
     * renders (expired-row GC only reclaims {@code SUCCEEDED} rows, so such a task
     * would linger forever). The renderer is scheduled only once that transaction
     * commits, so the worker cannot read a row that is not visible yet.
     * </p>
     */
    @Transactional
    public ExportTask create(UUID tenantId, UUID adminId, ExportFormat format, Instant from, Instant to,
            AuditContext context) {
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
        auditService.record(tenantId, context.actorId(), "EXPORT_CREATE", "EXPORT_TASK", task.id(),
                AuditSummaries.summary(context, "format", format.name(), "from", from.toString(), "to", to.toString()),
                context.requestId());
        scheduleAfterCommit(task);
        return task;
    }

    /**
     * Queues the renderer for the moment the enclosing transaction commits (same
     * idiom as {@code RouteRefreshPublisherAfterCommit}). Outside a transaction
     * there is nothing to wait for, so the task starts right away.
     */
    private void scheduleAfterCommit(ExportTask task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executor.execute(() -> run(task));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> run(task));
            }
        });
    }

    /** Task metadata (never the artifact bytes). */
    public ExportTask status(UUID tenantId, UUID taskId) {
        return find(tenantId, taskId);
    }

    /**
     * The finished artifact for download; EXPIRED or unfinished tasks are rejected.
     *
     * <p>
     * This is the only call through which the artifact bytes leave the control
     * plane, so a served download is audited ({@code EXPORT_DOWNLOAD}) against the
     * admin that fetched it — domain-model §7 lists the ExportJob's download among
     * the permanently recorded actions.
     * </p>
     */
    public ExportTask download(UUID tenantId, UUID taskId, AuditContext context) {
        ExportTask task = find(tenantId, taskId);
        if (task.status() != ExportStatus.SUCCEEDED || task.expiresAt() == null
                || task.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "EXPORT_EXPIRED",
                    "The export is not available for download (unfinished or expired)");
        }
        // After the expiry check: a rejected download never touched the bytes, so
        // it leaves no event (and the audit action means "artifact was served").
        auditService.record(tenantId, context.actorId(), "EXPORT_DOWNLOAD", "EXPORT_TASK", task.id(),
                AuditSummaries.summary(context, "format", task.format().name(), "rows", task.rowCount(), "bytes",
                        task.byteCount(), "sha256", task.sha256()),
                context.requestId());
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

    /**
     * Recent tasks for the open admin API (ADR-0015): metadata only, and the query
     * never reads {@code file_bytes} so artifact payloads stay off the machine
     * surface entirely.
     */
    public List<ExportTaskView> recentMeta(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT id, created_by, format, period_from, period_to, status, sha256, row_count, byte_count,
                       error_message, created_at, finished_at, expires_at, reconcile_level, adjustment_level
                FROM export_tasks WHERE tenant_id = :tenantId
                ORDER BY created_at DESC LIMIT :limit
                """,
                new MapSqlParameterSource("tenantId", tenantId).addValue("limit", Math.max(1, Math.min(limit, 50))),
                EXPORT_META_MAPPER);
    }

    /** One task's metadata for the open admin API (ADR-0015). */
    public ExportTaskView taskMeta(UUID tenantId, UUID taskId) {
        List<ExportTaskView> found = jdbc.query("""
                SELECT id, created_by, format, period_from, period_to, status, sha256, row_count, byte_count,
                       error_message, created_at, finished_at, expires_at, reconcile_level, adjustment_level
                FROM export_tasks WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource("id", taskId).addValue("tenantId", tenantId), EXPORT_META_MAPPER);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "Export task not found or not visible");
        }
        return found.get(0);
    }

    // -------------------------------------------------------------------

    private void run(ExportTask task) {
        mark(task.id(), ExportStatus.RUNNING, null);
        try {
            List<Map<String, Object>> rows = readRows(task);
            String reconcileLevel = reconcileLevelOf(rows);
            String adjustmentLevel = adjustmentLevelOf(rows);
            byte[] gzip = render(task.format(), rows, reconcileLevel, adjustmentLevel);
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gzip));
            jdbc.update("""
                    UPDATE export_tasks
                    SET status = 'SUCCEEDED', sha256 = :sha256, row_count = :rows, byte_count = :bytes,
                        file_bytes = :file, error_message = NULL, finished_at = :finishedAt,
                        expires_at = :expiresAt, reconcile_level = :reconcileLevel,
                        adjustment_level = :adjustmentLevel
                    WHERE id = :id
                    """,
                    new MapSqlParameterSource("sha256", sha256).addValue("rows", rows.size())
                            .addValue("bytes", gzip.length).addValue("file", gzip)
                            .addValue("reconcileLevel", reconcileLevel).addValue("adjustmentLevel", adjustmentLevel)
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
                SELECT ue.occurred_at, ue.model_id, ue.cache_level,
                       COALESCE(ue.input_tokens, ue.prompt_tokens) AS input_tokens,
                       COALESCE(ue.output_tokens, ue.completion_tokens) AS output_tokens,
                       ue.cache_read_input_tokens, ue.cache_creation_input_tokens, ue.total_tokens, ue.latency_ms,
                       ue.upstream_status_code, ue.provider_request_id, ue.gateway_request_id, ue.is_complete,
                       ue.usage_missing, ue.virtual_key_id, ue.project_id, ue.provider_product_id, ue.credential_id,
                       ue.client_ip,
                       -- Adjusted (net) counts alongside the observed ones (#709). The export is a
                       -- financial artefact, so it must carry the same netting the detail list and the
                       -- aggregates do — hence the shared fragments rather than a local copy.
                       %s AS net_input_tokens,
                       %s AS net_output_tokens,
                       %s AS net_cache_read_tokens,
                       %s AS net_cache_creation_tokens,
                       %s AS adjusted
                FROM usage_event ue%s
                WHERE ue.tenant_id = :tenantId AND ue.occurred_at >= :from AND ue.occurred_at < :to
                ORDER BY ue.occurred_at
                """.formatted(UsageAdjustmentSql.netInput(), UsageAdjustmentSql.netOutput(),
                UsageAdjustmentSql.netCacheRead(), UsageAdjustmentSql.netCacheCreation(),
                UsageAdjustmentSql.ADJUSTED_FLAG, UsageAdjustmentSql.ADJUSTMENT_LATERAL),
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
                    row.put("clientIp", rs.getString("client_ip"));
                    row.put("isComplete", rs.getBoolean("is_complete"));
                    row.put("usageMissing", rs.getBoolean("usage_missing"));
                    row.put("virtualKeyId", String.valueOf(rs.getObject("virtual_key_id")));
                    row.put("projectId", String.valueOf(rs.getObject("project_id")));
                    row.put("providerProductId", String.valueOf(rs.getObject("provider_product_id")));
                    row.put("credentialId",
                            rs.getObject("credential_id") != null
                                    ? String.valueOf(rs.getObject("credential_id"))
                                    : null);
                    row.put("netInputTokens", rs.getObject("net_input_tokens"));
                    row.put("netOutputTokens", rs.getObject("net_output_tokens"));
                    row.put("netCacheReadInputTokens", rs.getObject("net_cache_read_tokens"));
                    row.put("netCacheCreationInputTokens", rs.getObject("net_cache_creation_tokens"));
                    row.put("adjusted", rs.getBoolean("adjusted"));
                    return row;
                });
    }

    /**
     * The CSV's column order, declared once.
     *
     * <p>
     * Both the header and every data row are built from this list, so the two
     * cannot disagree. They used to be independent — a hand-written header literal
     * plus the row map's insertion order — and they silently drifted when
     * {@code client_ip} was added (V52/#605): the header kept 18 data columns while
     * each row carried 19, shifting {@code isComplete} and everything after it one
     * position. A misaligned export does not fail loudly; it just hands consumers
     * the wrong values under the right names (#754).
     * </p>
     *
     * <p>
     * JSONL needs no such list: it serialises the row map directly.
     * </p>
     */
    private static final List<String> CSV_COLUMN_ORDER = List.of("occurredAt", "modelId", "cacheLevel", "inputTokens",
            "outputTokens", "cacheReadInputTokens", "cacheCreationInputTokens", "totalTokens", "latencyMs",
            "upstreamStatusCode", "providerRequestId", "gatewayRequestId", "clientIp", "isComplete", "usageMissing",
            "virtualKeyId", "projectId", "providerProductId", "credentialId", "netInputTokens", "netOutputTokens",
            "netCacheReadInputTokens", "netCacheCreationInputTokens", "adjusted");

    /**
     * Issue #330: provider-request-id coverage determines the task's reconcile
     * level; an empty window has nothing to declare (null).
     */
    static String reconcileLevelOf(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        long withId = rows.stream().filter(r -> r.get("providerRequestId") != null).count();
        if (withId == rows.size()) {
            return "PROVIDER_ID_BACKED";
        }
        return withId == 0 ? "LOCAL_ONLY" : "PARTIAL";
    }

    /**
     * Issue #716: whether the file's numbers include corrections — the axis V41
     * left room for, stated per task so a consumer knows before reading the rows
     * whether the {@code net*} columns matter. An empty window has nothing to
     * declare (null).
     *
     * <p>
     * Reads the same per-row marker the file carries ({@code adjusted}, #709), so
     * the task-level declaration and the file cannot disagree.
     * </p>
     */
    static String adjustmentLevelOf(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        return rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("adjusted"))) ? "PRESENT" : "NONE";
    }

    /**
     * Note suffix for the file's local_caliber_note column (backwards compatible
     * prefix: {@code local-instant} always leads, and the tokens after it are
     * appended).
     */
    private static String caliberNote(String reconcileLevel, String adjustmentLevel) {
        if (reconcileLevel == null && adjustmentLevel == null) {
            return "local-instant";
        }
        StringBuilder note = new StringBuilder("local-instant");
        if (reconcileLevel != null) {
            note.append(";reconcile=").append(switch (reconcileLevel) {
                case "PROVIDER_ID_BACKED" -> "provider-id";
                case "PARTIAL" -> "mixed";
                default -> "local-only";
            });
        }
        if (adjustmentLevel != null) {
            note.append(";adjustments=").append("PRESENT".equals(adjustmentLevel) ? "present" : "none");
        }
        return note.toString();
    }

    /** Renders rows into the requested format and gzips the result. */
    private byte[] render(ExportFormat format, List<Map<String, Object>> rows, String reconcileLevel,
            String adjustmentLevel) throws Exception {
        String note = caliberNote(reconcileLevel, adjustmentLevel);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            if (format == ExportFormat.CSV) {
                gzip.write((String.join(",", CSV_COLUMN_ORDER) + ",local_caliber_note\n")
                        .getBytes(StandardCharsets.UTF_8));
                for (Map<String, Object> row : rows) {
                    // Read in the declared order, never the map's insertion order, so a
                    // reordering of the map can no longer silently shift the columns.
                    gzip.write(join(CSV_COLUMN_ORDER.stream().map(row::get).toList()).getBytes(StandardCharsets.UTF_8));
                    gzip.write(("," + note + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } else {
                for (Map<String, Object> row : rows) {
                    row.put("localCaliberNote", note);
                    gzip.write(objectMapper.writeValueAsBytes(row));
                    gzip.write('\n');
                }
            }
        }
        return out.toByteArray();
    }

    /**
     * One CSV cell. RFC 4180 quoting plus the #430 spreadsheet formula-injection
     * guard — the same semantics as {@code AuditEventReadService.quote} and
     * {@code ReconciliationService.quote}, because this export also ships
     * provider-controlled text ({@code providerRequestId} comes straight from the
     * upstream response) and gets opened in spreadsheets (#816).
     *
     * <p>
     * Numbers stay numbers: a negative amount renders as {@code -12.34}, not
     * {@code '-12.34} (which a spreadsheet would read as text and skip in SUM).
     * Package-private for the unit test, like its two siblings.
     * </p>
     */
    static String quote(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (value instanceof Number) {
            return text;
        }
        String guarded = text;
        if (!guarded.isEmpty() && "=+-@\t\r".indexOf(guarded.charAt(0)) >= 0) {
            guarded = "'" + guarded;
        }
        if (guarded.indexOf(',') < 0 && guarded.indexOf('"') < 0 && guarded.indexOf('\n') < 0
                && guarded.indexOf('\r') < 0) {
            return guarded;
        }
        return '"' + guarded.replace("\"", "\"\"") + '"';
    }

    private static String join(Iterable<Object> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(quote(v));
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

    /**
     * Reclaims finished artifacts past their 24h download window (F06): SUCCEEDED
     * rows whose {@code expires_at} passed are physically removed with their
     * payload. FAILED rows stay visible for operations review. Returns the number
     * of removed tasks. Single atomic DELETE — no surrounding transaction needed.
     */
    public int sweepExpired() {
        return jdbc.update("""
                DELETE FROM export_tasks
                WHERE status = 'SUCCEEDED' AND expires_at IS NOT NULL AND expires_at < now()
                """, new MapSqlParameterSource());
    }

    private static final RowMapper<ExportTask> ROW_MAPPER = (rs, rowNum) -> new ExportTask((UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("created_by"),
            ExportFormat.valueOf(rs.getString("format")), rs.getTimestamp("period_from").toInstant(),
            rs.getTimestamp("period_to").toInstant(), ExportStatus.valueOf(rs.getString("status")),
            rs.getString("sha256"), rs.getObject("row_count", Long.class), rs.getObject("byte_count", Long.class),
            rs.getBytes("file_bytes"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null,
            rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
            rs.getString("reconcile_level"), rs.getString("adjustment_level"));

    /** Metadata row mapper shared by the open-surface queries (no file_bytes). */
    private static final RowMapper<ExportTaskView> EXPORT_META_MAPPER = (rs, rowNum) -> new ExportTaskView(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("created_by"), ExportFormat.valueOf(rs.getString("format")),
            rs.getTimestamp("period_from").toInstant(), rs.getTimestamp("period_to").toInstant(),
            ExportStatus.valueOf(rs.getString("status")), rs.getString("sha256"), rs.getObject("row_count", Long.class),
            rs.getObject("byte_count", Long.class), rs.getString("error_message"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null,
            rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
            rs.getString("reconcile_level"), rs.getString("adjustment_level"));
}
