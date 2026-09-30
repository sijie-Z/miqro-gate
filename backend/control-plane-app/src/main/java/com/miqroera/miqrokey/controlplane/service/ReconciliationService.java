package com.miqroera.miqrokey.controlplane.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.service.reconciliation.BillReconciliationEngine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.CanonicalBillParser;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BillLine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BucketDiff;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.LocalUsageRow;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Parsed;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Report;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.RowResult;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Verdict;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Bill reconciliation endpoints (issue #334, F19 contract draft v0): canonical
 * JSONL upload → async parse → four-level match → persisted four-state report.
 * Results are read-only — usage_event is never written, and the uploaded
 * content is never stored (SHA-256 + size only). Idempotent re-upload of the
 * same (provider, window, currency, content) resolves to the existing report.
 * Provider-specific parsers and the fingerprint match level remain
 * WAITING_FOR_SAMPLE.
 */
@Service
public class ReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);

    static final Duration MAX_WINDOW = Duration.ofDays(31);
    static final int MAX_UPLOAD_BYTES = 16 * 1024 * 1024;
    static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;
    static final int MAX_LINES = 100_000;
    static final int ROWS_PAGE_MAX = 500;
    /** Single-download row cap, same 5 万行 bound as the other CSV exports. */
    static final int EXPORT_MAX_ROWS = 50_000;

    /**
     * Local {@code usage_event} rows one run may read (#1422). The upload side has
     * had {@link #MAX_LINES} all along while the read side had nothing, so a
     * 170-byte one-line bill over a busy window materialised the entire window and
     * OOM-killed the control plane (whose heap carries
     * {@code -XX:+ExitOnOutOfMemoryError} in production).
     *
     * <p>
     * Deliberately the same number as {@link #MAX_LINES}: a window holding more
     * local rows than a bill may carry lines cannot be reconciled line by line
     * anyway, and one ceiling is one thing to learn.
     */
    static final int MAX_LOCAL_ROWS = 100_000;

    /**
     * Rows per write page (#1422). A single {@code batchUpdate} over the whole
     * report buffers every parameter set in pgjdbc before executing, so the write
     * side used to hold a second full copy of the report.
     */
    static final int ROWS_WRITE_PAGE = 500;

    private static final String INSERT_ROW = """
            INSERT INTO reconciliation_rows (id, report_id, tenant_id, row_no, verdict, matched_by,
                provider_row_ref, local_ref, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    /**
     * Declared export column order — header and every data row are built from this
     * one list (#754): a misaligned export does not fail loudly, it hands consumers
     * the wrong values under the right names.
     *
     * <p>
     * {@code detail_*} columns flatten the heterogeneous per-verdict {@code detail}
     * JSON ({@code modelId/amount/currency/occurredAt/status} for bill rows,
     * {@code bucketKey/providerCount/localCount} for PARTIAL,
     * {@code occurredAt/modelId} for UNMATCHED_LOCAL); cells a verdict does not
     * carry stay empty rather than shifting columns.
     */
    static final List<String> EXPORT_COLUMNS = List.of("report_id", "provider_code", "row_no", "verdict", "matched_by",
            "provider_row_ref", "local_ref", "detail_model_id", "detail_amount", "detail_currency",
            "detail_occurred_at", "detail_status", "detail_bucket_key", "detail_provider_count", "detail_local_count");

    private static final String DETAIL_PREFIX = "detail_";

    /**
     * {@code detail_*} column → the key it reads from the stored detail JSON. The
     * header is snake_case like the other admin exports while the JSON is
     * camelCase, so the two cannot be derived from each other: a column must be
     * spelled out here, and {@code ReconciliationExportCsvTest} fails if the
     * declared columns and these keys ever drift apart.
     */
    static final Map<String, String> DETAIL_KEYS = Map.of("detail_model_id", "modelId", "detail_amount", "amount",
            "detail_currency", "currency", "detail_occurred_at", "occurredAt", "detail_status", "status",
            "detail_bucket_key", "bucketKey", "detail_provider_count", "providerCount", "detail_local_count",
            "localCount");

    /**
     * Import identity → the live report already stored for it, if any.
     * {@code FAILED} runs are excluded so a failed bill stays re-runnable (#451).
     */
    private static final String SELECT_LIVE_REPORT = """
            SELECT id FROM reconciliation_reports
            WHERE tenant_id = :tenantId AND provider_code = :code AND currency = :currency
              AND window_from = :from AND window_to = :to AND upload_sha256 = :sha
              AND status <> 'FAILED'
            ORDER BY created_at DESC LIMIT 1
            """;

    private static final String INSERT_REPORT = """
            INSERT INTO reconciliation_reports (id, tenant_id, created_by, provider_code, currency, window_from,
                window_to, status, upload_sha256, upload_bytes, created_at)
            VALUES (:id, :tenantId, :createdBy, :code, :currency, :from, :to, 'PENDING', :sha, :bytes, now())
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final CanonicalBillParser parser;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "reconciliation");
        t.setDaemon(true);
        return t;
    });

    /**
     * #451: runs execute only in this process — anything PENDING/RUNNING after a
     * restart was interrupted mid-flight and can never finish. Marked FAILED at
     * startup so the idempotent re-upload path treats the bill as re-runnable
     * instead of returning a zombie forever.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        try {
            int recovered = jdbc.update("""
                    UPDATE reconciliation_reports
                    SET status = 'FAILED', error_message = 'interrupted by restart'
                    WHERE status IN ('PENDING', 'RUNNING')
                    """, new MapSqlParameterSource());
            if (recovered > 0) {
                LOG.warn("reconciliation: marked {} interrupted run(s) FAILED after restart", recovered);
            }
        } catch (Exception e) {
            // Best-effort maintenance: an environment without the table (H2
            // smoke contexts, brand-new databases) must still boot.
            LOG.warn("reconciliation: interrupted-run recovery skipped: {}", e.getMessage());
        }
    }

    public ReconciliationService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, AuditService auditService,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.parser = new CanonicalBillParser(objectMapper);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ API

    public Map<String, Object> create(UUID tenantId, AuditContext context, String providerCode, String currency,
            Instant windowFrom, Instant windowTo, byte[] upload) {
        if (providerCode == null || providerCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_PROVIDER_UNKNOWN", "providerCode 必填。");
        }
        String code = providerCode.trim();
        if (jdbc.queryForObject("SELECT count(*) FROM provider_products WHERE product_code = :code",
                Map.of("code", code), Integer.class) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_PROVIDER_UNKNOWN",
                    "供应商目录中不存在该 product_code。");
        }
        if (currency == null || !currency.trim().matches("[A-Za-z]{3}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_PARAM_INVALID", "currency 必须是 ISO-4217。");
        }
        if (windowFrom == null || windowTo == null || !windowFrom.isBefore(windowTo)
                || Duration.between(windowFrom, windowTo).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_WINDOW_INVALID", "窗口必填、from<to 且不超过 31 天。");
        }
        if (upload == null || upload.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_UPLOAD_INVALID", "上传内容为空。");
        }
        if (upload.length > MAX_UPLOAD_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_UPLOAD_INVALID", "上传超过 16MB 上限。");
        }
        byte[] content = decompress(upload);
        String sha256 = HexFormat.of().formatHex(sha256(upload));

        // Idempotent re-upload: the same provider + window + currency + content
        // resolves to the existing (non-failed) report without re-running. Lookup and
        // insert are one critical section — see findOrCreateReport.
        ImportOutcome outcome = findOrCreateReport(tenantId, context.actorId(), code, currency.trim().toUpperCase(),
                windowFrom, windowTo, sha256, upload.length);
        if (outcome.created()) {
            // Audit and parse only for the call that actually created the report: the
            // deduped caller must not append a second RECONCILIATION_CREATED nor start
            // a second parse of the same bill.
            auditService.record(tenantId, context.actorId(), "RECONCILIATION_CREATED", "RECONCILIATION",
                    outcome.reportId(),
                    AuditSummaries.summary(context, "providerCode", code, "windowFrom", windowFrom.toString(),
                            "windowTo", windowTo.toString(), "uploadSha256", sha256, "uploadBytes",
                            String.valueOf(upload.length)),
                    context.requestId());
            executor.execute(() -> run(tenantId, outcome.reportId(), content));
        }
        return get(tenantId, outcome.reportId());
    }

    /**
     * Find-or-create for one reconciliation import identity, in a single critical
     * section.
     *
     * <p>
     * The dedupe lookup and the insert are a check-then-act pair, and the index
     * behind them ({@code idx_reconciliation_reports_dedupe}, V42) is not unique:
     * two uploads of the same bill that are in flight at once both miss the lookup
     * and both insert, so the contract's 「重复导入返回既有报告（不重复执行）」 is broken and the bill
     * is parsed twice. A transaction-scoped advisory lock over the identity
     * serialises the pair across the cluster — the same mechanism the audit chain
     * ({@code AdminAuditEventRepositoryImpl}) and the adjustment ledger
     * ({@code UsageAdjustmentRepositoryImpl#lockUsageEvent}) already use. Unlike a
     * unique index it needs no migration, so it cannot fail to start on a database
     * that already holds duplicates.
     * </p>
     */
    private ImportOutcome findOrCreateReport(UUID tenantId, UUID createdBy, String code, String currency,
            Instant windowFrom, Instant windowTo, String sha256, long uploadBytes) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId).addValue("code", code)
                .addValue("currency", currency).addValue("from", java.sql.Timestamp.from(windowFrom))
                .addValue("to", java.sql.Timestamp.from(windowTo)).addValue("sha", sha256);
        return transactionTemplate.execute(status -> {
            jdbc.getJdbcTemplate().query("SELECT pg_advisory_xact_lock(?)", rs -> {
            }, importLockKey(tenantId, code, currency, windowFrom, windowTo, sha256));
            List<UUID> existing = jdbc.queryForList(SELECT_LIVE_REPORT, params, UUID.class);
            if (!existing.isEmpty()) {
                return new ImportOutcome(existing.get(0), false);
            }
            UUID reportId = UUID.randomUUID();
            jdbc.update(INSERT_REPORT,
                    params.addValue("id", reportId).addValue("createdBy", createdBy).addValue("bytes", uploadBytes));
            return new ImportOutcome(reportId, true);
        });
    }

    /**
     * 64-bit advisory-lock key for one import identity. Hashing keeps the key in
     * range; a collision between unrelated identities only queues two uploads that
     * were never going to dedupe, it can never merge two reports.
     */
    static long importLockKey(UUID tenantId, String code, String currency, Instant windowFrom, Instant windowTo,
            String sha256) {
        String identity = String.join("|", String.valueOf(tenantId), code, currency, String.valueOf(windowFrom),
                String.valueOf(windowTo), sha256);
        return ByteBuffer.wrap(sha256(identity.getBytes(StandardCharsets.UTF_8))).getLong();
    }

    /**
     * The report an upload resolved to, and whether this call is the one that
     * created it.
     */
    private record ImportOutcome(UUID reportId, boolean created) {
    }

    /**
     * Tenant reports, newest first (limit 1..100; out-of-range is rejected rather
     * than silently clamped so callers never lose rows without noticing).
     */
    public Map<String, Object> list(UUID tenantId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_PARAM_INVALID", "limit 必须在 1..100。");
        }
        List<Map<String, Object>> reports = jdbc.queryForList("""
                SELECT * FROM reconciliation_reports
                WHERE tenant_id = :tenantId
                ORDER BY created_at DESC, id
                LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("limit", limit));
        List<Map<String, Object>> views = new ArrayList<>(reports.size());
        for (Map<String, Object> report : reports) {
            views.add(view(report));
        }
        return Map.of("reports", views);
    }

    public Map<String, Object> get(UUID tenantId, UUID reportId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT * FROM reconciliation_reports WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource("id", reportId).addValue("tenantId", tenantId));
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECONCILIATION_NOT_FOUND", "对账报告不存在。");
        }
        return view(found.get(0));
    }

    public Map<String, Object> rows(UUID tenantId, UUID reportId, String state, Long cursor, int limit) {
        get(tenantId, reportId); // existence + tenant check
        Integer effectiveLimit = Math.min(Math.max(limit, 1), ROWS_PAGE_MAX);
        String verdict = normalizeVerdict(state);
        MapSqlParameterSource params = new MapSqlParameterSource("reportId", reportId)
                .addValue("cursor", cursor == null ? 0L : cursor).addValue("limit", effectiveLimit);
        String filter = "";
        if (verdict != null) {
            filter = " AND verdict = :verdict ";
            params.addValue("verdict", verdict);
        }
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT row_no, verdict, matched_by, provider_row_ref, local_ref, detail
                FROM reconciliation_rows
                WHERE report_id = :reportId AND row_no > :cursor %s
                ORDER BY row_no LIMIT :limit
                """.formatted(filter), params, rowMapper);
        Long nextCursor = rows.size() == effectiveLimit
                ? ((Number) rows.get(rows.size() - 1).get("rowNo")).longValue()
                : null;
        return Map.of("rows", rows, "nextCursor", nextCursor == null ? "" : nextCursor);
    }

    /**
     * Four-state detail rows as a downloadable CSV (api-contract §5.27).
     *
     * <p>
     * Dialect: the synchronous admin-download convention of
     * {@code AdminAuditController} / {@code AdminRetentionLogController} — UTF-8
     * BOM, RFC 4180 quoting with the #430 formula-injection guard, one trailing
     * {@code X-MiQroKey-Truncated} declaration at {@link #EXPORT_MAX_ROWS}. The
     * async artifact dialect ({@code ExportTaskService}: gzip, camelCase,
     * {@code \,} escaping) is for queued jobs, not for a bounded download derived
     * from one report.
     *
     * <p>
     * {@code state} narrows the export exactly like the console's verdict filter
     * (same validation as {@link #rows}); an empty result is a header-only CSV,
     * never an error.
     */
    public CsvExport exportCsv(UUID tenantId, UUID reportId, String state) {
        Map<String, Object> report = get(tenantId, reportId); // existence + tenant check
        String verdict = normalizeVerdict(state);
        MapSqlParameterSource params = new MapSqlParameterSource("reportId", reportId).addValue("limit",
                EXPORT_MAX_ROWS + 1);
        String filter = "";
        if (verdict != null) {
            filter = " AND verdict = :verdict ";
            params.addValue("verdict", verdict);
        }
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT row_no, verdict, matched_by, provider_row_ref, local_ref, detail
                FROM reconciliation_rows
                WHERE report_id = :reportId %s
                ORDER BY row_no LIMIT :limit
                """.formatted(filter), params, rowMapper);
        boolean truncated = rows.size() > EXPORT_MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, EXPORT_MAX_ROWS);
        }
        StringBuilder csv = new StringBuilder(rows.size() * 128 + 160);
        csv.append('﻿'); // UTF-8 BOM so spreadsheet consumers detect the encoding
        csv.append(String.join(",", EXPORT_COLUMNS)).append('\n');
        for (Map<String, Object> row : rows) {
            StringBuilder line = new StringBuilder(128);
            for (String column : EXPORT_COLUMNS) {
                if (line.length() > 0) {
                    line.append(',');
                }
                line.append(csvCell(exportCell(row, report, column)));
            }
            csv.append(line).append('\n');
        }
        return new CsvExport(csv.toString(), rows.size(), truncated);
    }

    /** CSV export payload: rendered csv, exported row count, and the cap flag. */
    public record CsvExport(String csv, int rows, boolean truncated) {
    }

    /**
     * Verdict filter shared by the paged view and the export (both reject junk the
     * same way).
     */
    private static String normalizeVerdict(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return Verdict.valueOf(state.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_PARAM_INVALID",
                    "state 必须是 MATCHED/PARTIAL/UNMATCHED_PROVIDER/UNMATCHED_LOCAL。");
        }
    }

    /**
     * One declared column of one row. {@code detail_*} reads its declared key from
     * the per-verdict detail JSON; a verdict that does not carry the key yields an
     * empty cell.
     */
    static String exportCell(Map<String, Object> row, Map<String, Object> report, String column) {
        if (column.startsWith(DETAIL_PREFIX)) {
            String key = DETAIL_KEYS.get(column);
            if (key == null) {
                throw new IllegalStateException("未声明的导出列: " + column);
            }
            JsonNode detail = (JsonNode) row.get("detail");
            JsonNode value = detail == null ? null : detail.get(key);
            return value == null || value.isNull() ? "" : value.asText();
        }
        return switch (column) {
            case "report_id" -> String.valueOf(report.get("id"));
            case "provider_code" -> String.valueOf(report.get("providerCode"));
            case "row_no" -> String.valueOf(row.get("rowNo"));
            case "verdict" -> String.valueOf(row.get("verdict"));
            case "matched_by" -> stringValue(row.get("matchedBy"));
            case "provider_row_ref" -> stringValue(row.get("providerRowRef"));
            case "local_ref" -> stringValue(row.get("localRef"));
            default -> throw new IllegalStateException("未声明的导出列: " + column);
        };
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * A bare decimal literal — digits with at most one sign, point and exponent.
     * Nothing else; this decides whether a cell needs the formula guard at all.
     */
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    /**
     * RFC 4180 cell with the #430 spreadsheet formula-injection guard, mirroring
     * {@code AuditEventReadService.quote}: a cell starting with {@code = + - @
     * TAB CR} is prefixed with an apostrophe (execution neutralised). Bill refs and
     * detail fields carry provider-file text, so the guard applies here too.
     *
     * <p>
     * A cell that is *only* a decimal literal is exempt: {@code -12.34} is a
     * number, not a formula, and {@code CanonicalBillParser} accepts any amount
     * that is a decimal literal, so refund/adjustment lines legitimately carry a
     * negative one. Prefixing those would export {@code '-12.34} where the page
     * renders {@code -12.34}, and the amount column would reach the spreadsheet as
     * text that {@code SUM} ignores. Anything that merely starts like a number
     * ({@code -1+1}, {@code +cmd|' /C calc'!A0}) is still guarded. Package-private
     * for the unit test.
     */
    static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String guarded = value;
        if (!guarded.isEmpty() && !NUMERIC_LITERAL.matcher(guarded).matches()
                && "=+-@\t\r".indexOf(guarded.charAt(0)) >= 0) {
            guarded = "'" + guarded;
        }
        if (guarded.indexOf(',') < 0 && guarded.indexOf('"') < 0 && guarded.indexOf('\n') < 0
                && guarded.indexOf('\r') < 0) {
            return guarded;
        }
        return '"' + guarded.replace("\"", "\"\"") + '"';
    }

    /**
     * Shared row projection: one SELECT column set, one mapping, so the paged view
     * and the export cannot drift apart (both read the same keys).
     */
    private final RowMapper<Map<String, Object>> rowMapper = (rs, n) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rowNo", rs.getLong("row_no"));
        row.put("verdict", rs.getString("verdict"));
        row.put("matchedBy", rs.getString("matched_by"));
        row.put("providerRowRef", rs.getString("provider_row_ref"));
        row.put("localRef", rs.getString("local_ref"));
        row.put("detail", readTree(rs.getString("detail")));
        return row;
    };

    // ------------------------------------------------------------------ run

    private void run(UUID tenantId, UUID reportId, byte[] content) {
        try {
            jdbc.update("UPDATE reconciliation_reports SET status = 'RUNNING' WHERE id = :id",
                    new MapSqlParameterSource("id", reportId));
            Map<String, Object> meta = jdbc.queryForMap(
                    "SELECT provider_code, currency, window_from, window_to FROM reconciliation_reports WHERE id = :id",
                    new MapSqlParameterSource("id", reportId));
            Instant from = ((java.sql.Timestamp) meta.get("window_from")).toInstant();
            Instant to = ((java.sql.Timestamp) meta.get("window_to")).toInstant();

            Parsed parsed = parser.parse(new String(content, StandardCharsets.UTF_8));
            if (parsed.lines().size() > MAX_LINES) {
                fail(tenantId, reportId, "上传行数超过 " + MAX_LINES + " 上限");
                return;
            }
            if (parsed.lines().isEmpty() && !parsed.errors().isEmpty()) {
                String first = parsed.errors().get(0).lineNumber() + ": " + parsed.errors().get(0).detail();
                fail(tenantId, reportId, "无可用的账单行（" + first + "）");
                return;
            }
            List<LocalUsageRow> locals = readLocals(tenantId, from, to);
            if (locals.size() > MAX_LOCAL_ROWS) {
                // Refuse rather than reconcile a silently truncated window: a short
                // financial report reads as "nothing else happened" (#1422).
                fail(tenantId, reportId, "窗口内本地用量行数超过 " + MAX_LOCAL_ROWS + " 上限，请缩小窗口后重试");
                return;
            }
            Report report = BillReconciliationEngine.reconcile(parsed.lines(), locals, from, to);

            try {
                writeRows(tenantId, reportId, report, parsed.lines(), locals);
            } catch (Exception e) {
                jdbc.update("DELETE FROM reconciliation_rows WHERE report_id = :id",
                        new MapSqlParameterSource("id", reportId));
                throw e;
            }
            jdbc.update("""
                    UPDATE reconciliation_reports
                    SET status = 'SUCCEEDED', total_rows = :total, matched = :matched, partial_buckets = :partial,
                        unmatched_provider = :unmatchedProvider, unmatched_local = :unmatchedLocal,
                        line_error_count = :lineErrors, amount_diff = :amountDiff, error_message = NULL,
                        finished_at = now()
                    WHERE id = :id
                    """, new MapSqlParameterSource("total", report.total()).addValue("matched", report.matched())
                    .addValue("partial", report.partial()).addValue("unmatchedProvider", report.unmatchedProvider())
                    .addValue("unmatchedLocal", report.unmatchedLocal()).addValue("lineErrors", parsed.errors().size())
                    .addValue("amountDiff", report.amountDiff()).addValue("id", reportId));
            auditService.record(tenantId, null, "RECONCILIATION_SUCCEEDED", "RECONCILIATION", reportId,
                    AuditSummaries.summary("providerCode", String.valueOf(meta.get("provider_code")), "total",
                            String.valueOf(report.total()), "matched", String.valueOf(report.matched()),
                            "unmatchedProvider", String.valueOf(report.unmatchedProvider()), "unmatchedLocal",
                            String.valueOf(report.unmatchedLocal()), "amountDiff", report.amountDiff().toPlainString()),
                    null);
        } catch (Exception e) {
            LOG.warn("Reconciliation {} failed", reportId, e);
            fail(tenantId, reportId, truncate(e.getMessage()));
        }
    }

    private void fail(UUID tenantId, UUID reportId, String message) {
        jdbc.update("""
                UPDATE reconciliation_reports SET status = 'FAILED', error_message = :error, finished_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource("error", message).addValue("id", reportId));
        auditService.record(tenantId, null, "RECONCILIATION_FAILED", "RECONCILIATION", reportId,
                AuditSummaries.summary("error", message == null ? "unknown" : message), null);
    }

    /**
     * The window's local usage rows, capped at {@link #MAX_LOCAL_ROWS} (#1422). One
     * row past the cap is fetched so the caller can distinguish "exactly at the
     * cap" from "over it" in the same query — a separate {@code COUNT} would both
     * double the work and race the read.
     *
     * <p>
     * Below the cap the {@code LIMIT} is inert and the ordering stays irrelevant:
     * the engine matches against the whole set, not a prefix.
     */
    private List<LocalUsageRow> readLocals(UUID tenantId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT e.id, e.provider_request_id, e.occurred_at, e.model_id, p.product_code,
                       COALESCE(e.input_tokens, e.prompt_tokens) AS input_tokens,
                       COALESCE(e.output_tokens, e.completion_tokens) AS output_tokens,
                       e.cache_read_input_tokens,
                       (e.upstream_status_code BETWEEN 200 AND 299) AS success
                FROM usage_event e JOIN provider_products p ON p.id = e.provider_product_id
                WHERE e.tenant_id = :tenantId AND e.occurred_at >= :from AND e.occurred_at < :to
                LIMIT :max
                """,
                new MapSqlParameterSource("tenantId", tenantId).addValue("from", java.sql.Timestamp.from(from))
                        .addValue("to", java.sql.Timestamp.from(to)).addValue("max", MAX_LOCAL_ROWS + 1),
                (rs, n) -> new LocalUsageRow(rs.getObject("id").toString(), rs.getString("provider_request_id"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("model_id"),
                        rs.getString("product_code"), rs.getObject("input_tokens", Long.class),
                        rs.getObject("output_tokens", Long.class), rs.getObject("cache_read_input_tokens", Long.class),
                        rs.getBoolean("success")));
    }

    /**
     * Assembles the report rows and writes them out {@link #ROWS_WRITE_PAGE} at a
     * time (#1422). The rows themselves are unchanged; only their lifetime is —
     * previously the whole report was materialised as one {@code List<Object[]>}
     * and handed to a single {@code batchUpdate}, which pgjdbc buffers in full
     * before executing, so the write side held a second copy of the report sized by
     * the window rather than by the upload.
     *
     * <p>
     * A failure part-way through leaves earlier pages committed; the caller's
     * compensating {@code DELETE} is what preserves the no-partial-report rule.
     */
    private void writeRows(UUID tenantId, UUID reportId, Report report, List<BillLine> bills,
            List<LocalUsageRow> locals) {
        RowPageWriter writer = new RowPageWriter();
        assemblyRows(tenantId, reportId, report, bills, locals, writer);
        writer.flush();
    }

    private final class RowPageWriter implements Consumer<Object[]> {

        private final List<Object[]> page = new ArrayList<>(ROWS_WRITE_PAGE);

        @Override
        public void accept(Object[] row) {
            page.add(row);
            if (page.size() >= ROWS_WRITE_PAGE) {
                flush();
            }
        }

        private void flush() {
            if (!page.isEmpty()) {
                jdbc.getJdbcTemplate().batchUpdate(INSERT_ROW, page);
                page.clear();
            }
        }
    }

    /** Row rows: bill lines (aligned by index) → buckets → unmatched locals. */
    private void assemblyRows(UUID tenantId, UUID reportId, Report report, List<BillLine> bills,
            List<LocalUsageRow> locals, Consumer<Object[]> sink) {
        int rowNo = 1;
        for (int i = 0; i < report.rows().size(); i++) {
            RowResult result = report.rows().get(i);
            BillLine bill = bills.get(i);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("modelId", bill.modelId());
            detail.put("amount", bill.amount());
            detail.put("currency", bill.currency());
            detail.put("occurredAt", bill.occurredAt() == null ? null : bill.occurredAt().toString());
            detail.put("status", bill.status());
            sink.accept(new Object[]{UUID.randomUUID(), reportId, tenantId, rowNo++, result.verdict().name(),
                    result.level() == null || result.level().name().equals("NONE") ? null : result.level().name(),
                    result.providerRowRef(), result.localRef(), json(detail)});
        }
        for (BucketDiff bucket : report.buckets()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("bucketKey", bucket.productCode());
            detail.put("providerCount", bucket.providerCount());
            detail.put("localCount", bucket.localCount());
            sink.accept(new Object[]{UUID.randomUUID(), reportId, tenantId, rowNo++, "PARTIAL", null, null, null,
                    json(detail)});
        }
        Map<String, LocalUsageRow> localsByRef = new LinkedHashMap<>();
        locals.forEach(l -> localsByRef.put(l.localRef(), l));
        for (RowResult result : report.unmatchedLocalRows()) {
            LocalUsageRow local = localsByRef.get(result.localRef());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("occurredAt",
                    local == null || local.occurredAt() == null ? null : local.occurredAt().toString());
            detail.put("modelId", local == null ? null : local.modelId());
            sink.accept(new Object[]{UUID.randomUUID(), reportId, tenantId, rowNo++, "UNMATCHED_LOCAL", null, null,
                    result.localRef(), json(detail)});
        }
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode readTree(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** Metadata view (also the create/get return shape — never re-wrapped). */
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(row.get("id")));
        view.put("providerCode", row.get("provider_code"));
        view.put("currency", row.get("currency"));
        view.put("windowFrom", ((java.sql.Timestamp) row.get("window_from")).toInstant().toString());
        view.put("windowTo", ((java.sql.Timestamp) row.get("window_to")).toInstant().toString());
        view.put("status", row.get("status"));
        view.put("uploadSha256", row.get("upload_sha256"));
        view.put("uploadBytes", row.get("upload_bytes"));
        view.put("totalRows", row.get("total_rows"));
        view.put("matched", row.get("matched"));
        view.put("partialBuckets", row.get("partial_buckets"));
        view.put("unmatchedProvider", row.get("unmatched_provider"));
        view.put("unmatchedLocal", row.get("unmatched_local"));
        view.put("lineErrorCount", row.get("line_error_count"));
        BigDecimal diff = (BigDecimal) row.get("amount_diff");
        view.put("amountDiff", diff == null ? null : diff.toPlainString());
        view.put("errorMessage", row.get("error_message"));
        view.put("createdBy", String.valueOf(row.get("created_by")));
        view.put("createdAt", ((java.sql.Timestamp) row.get("created_at")).toInstant().toString());
        Object finished = row.get("finished_at");
        view.put("finishedAt", finished == null ? null : ((java.sql.Timestamp) finished).toInstant().toString());
        return view;
    }

    private byte[] decompress(byte[] upload) {
        boolean gzip = upload.length >= 2 && upload[0] == (byte) 0x1f && upload[1] == (byte) 0x8b;
        if (!gzip) {
            return upload;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(upload))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_UPLOAD_INVALID", "解压后超过 64MB 上限。");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_UPLOAD_INVALID", "gzip 解压失败。");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
