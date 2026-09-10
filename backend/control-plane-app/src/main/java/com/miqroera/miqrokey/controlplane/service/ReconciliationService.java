package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final CanonicalBillParser parser;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "reconciliation");
        t.setDaemon(true);
        return t;
    });

    public ReconciliationService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
            AuditService auditService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.parser = new CanonicalBillParser(objectMapper);
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
        // resolves to the existing (non-failed) report without re-running.
        List<UUID> existing = jdbc.queryForList("""
                SELECT id FROM reconciliation_reports
                WHERE tenant_id = :tenantId AND provider_code = :code AND currency = :currency
                  AND window_from = :from AND window_to = :to AND upload_sha256 = :sha
                  AND status <> 'FAILED'
                ORDER BY created_at DESC LIMIT 1
                """,
                new MapSqlParameterSource("tenantId", tenantId).addValue("code", code)
                        .addValue("currency", currency.trim().toUpperCase())
                        .addValue("from", java.sql.Timestamp.from(windowFrom))
                        .addValue("to", java.sql.Timestamp.from(windowTo)).addValue("sha", sha256),
                UUID.class);
        if (!existing.isEmpty()) {
            return get(tenantId, existing.get(0));
        }

        UUID reportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_reports (id, tenant_id, created_by, provider_code, currency, window_from,
                    window_to, status, upload_sha256, upload_bytes, created_at)
                VALUES (:id, :tenantId, :createdBy, :code, :currency, :from, :to, 'PENDING', :sha, :bytes, now())
                """, new MapSqlParameterSource("id", reportId).addValue("tenantId", tenantId)
                .addValue("createdBy", context.actorId()).addValue("code", code)
                .addValue("currency", currency.trim().toUpperCase())
                .addValue("from", java.sql.Timestamp.from(windowFrom)).addValue("to", java.sql.Timestamp.from(windowTo))
                .addValue("sha", sha256).addValue("bytes", (long) upload.length));
        auditService.record(tenantId, context.actorId(), "RECONCILIATION_CREATED", "RECONCILIATION", reportId,
                AuditSummaries.summary(context, "providerCode", code, "windowFrom", windowFrom.toString(), "windowTo",
                        windowTo.toString(), "uploadSha256", sha256, "uploadBytes", String.valueOf(upload.length)),
                context.requestId());
        executor.execute(() -> run(tenantId, reportId, content));
        return get(tenantId, reportId);
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
        String verdict = null;
        if (state != null && !state.isBlank()) {
            try {
                verdict = Verdict.valueOf(state.trim().toUpperCase()).name();
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RECONCILIATION_PARAM_INVALID",
                        "state 必须是 MATCHED/PARTIAL/UNMATCHED_PROVIDER/UNMATCHED_LOCAL。");
            }
        }
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
                """.formatted(filter), params, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowNo", rs.getLong("row_no"));
            row.put("verdict", rs.getString("verdict"));
            row.put("matchedBy", rs.getString("matched_by"));
            row.put("providerRowRef", rs.getString("provider_row_ref"));
            row.put("localRef", rs.getString("local_ref"));
            row.put("detail", readTree(rs.getString("detail")));
            return row;
        });
        Long nextCursor = rows.size() == effectiveLimit
                ? ((Number) rows.get(rows.size() - 1).get("rowNo")).longValue()
                : null;
        return Map.of("rows", rows, "nextCursor", nextCursor == null ? "" : nextCursor);
    }

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
            Report report = BillReconciliationEngine.reconcile(parsed.lines(), locals, from, to);

            List<Object[]> rowBatch = assemblyRows(tenantId, reportId, report, parsed.lines(), locals);
            try {
                jdbc.getJdbcTemplate().batchUpdate("""
                        INSERT INTO reconciliation_rows (id, report_id, tenant_id, row_no, verdict, matched_by,
                            provider_row_ref, local_ref, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """, rowBatch);
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

    private List<LocalUsageRow> readLocals(UUID tenantId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT e.id, e.provider_request_id, e.occurred_at, e.model_id, p.product_code,
                       COALESCE(e.input_tokens, e.prompt_tokens) AS input_tokens,
                       COALESCE(e.output_tokens, e.completion_tokens) AS output_tokens,
                       e.cache_read_input_tokens,
                       (e.upstream_status_code BETWEEN 200 AND 299) AS success
                FROM usage_event e JOIN provider_products p ON p.id = e.provider_product_id
                WHERE e.tenant_id = :tenantId AND e.occurred_at >= :from AND e.occurred_at <= :to
                """,
                new MapSqlParameterSource("tenantId", tenantId).addValue("from", java.sql.Timestamp.from(from))
                        .addValue("to", java.sql.Timestamp.from(to)),
                (rs, n) -> new LocalUsageRow(rs.getObject("id").toString(), rs.getString("provider_request_id"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("model_id"),
                        rs.getString("product_code"), rs.getObject("input_tokens", Long.class),
                        rs.getObject("output_tokens", Long.class), rs.getObject("cache_read_input_tokens", Long.class),
                        rs.getBoolean("success")));
    }

    /** Row rows: bill lines (aligned by index) → buckets → unmatched locals. */
    private List<Object[]> assemblyRows(UUID tenantId, UUID reportId, Report report, List<BillLine> bills,
            List<LocalUsageRow> locals) {
        List<Object[]> batch = new ArrayList<>();
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
            batch.add(new Object[]{UUID.randomUUID(), reportId, tenantId, rowNo++, result.verdict().name(),
                    result.level() == null || result.level().name().equals("NONE") ? null : result.level().name(),
                    result.providerRowRef(), result.localRef(), json(detail)});
        }
        for (BucketDiff bucket : report.buckets()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("bucketKey", bucket.productCode());
            detail.put("providerCount", bucket.providerCount());
            detail.put("localCount", bucket.localCount());
            batch.add(new Object[]{UUID.randomUUID(), reportId, tenantId, rowNo++, "PARTIAL", null, null, null,
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
            batch.add(new Object[]{UUID.randomUUID(), reportId, tenantId, rowNo++, "UNMATCHED_LOCAL", null, null,
                    result.localRef(), json(detail)});
        }
        return batch;
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
