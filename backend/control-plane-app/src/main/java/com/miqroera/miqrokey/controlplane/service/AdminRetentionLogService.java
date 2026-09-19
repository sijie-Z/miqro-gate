package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AdminRetentionLogView;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin-side reader of {@code retention_log} (ADR-0014 §8): filters, decrypts
 * for the authorized (SYSTEM_ADMIN) view and renders CSV exports. Decryption
 * happens here and only here — plaintext never leaves the response of an
 * audited admin action. Rows whose crypto material cannot be decrypted (key
 * version retired, crypto disabled) surface with {@code text = null} instead of
 * failing the page.
 */
@Service
public class AdminRetentionLogService {

    /** Same cap as the audit export (api-contract §8). */
    public static final int EXPORT_LIMIT = 50_000;
    public static final int MAX_PAGE_SIZE = 100;
    /** Rows read per query while streaming the export (#1023): the only thing bounding memory. */
    public static final int EXPORT_CHUNK = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<KeyEncryptionProvider> cryptoProvider;

    public AdminRetentionLogService(NamedParameterJdbcTemplate jdbc,
            ObjectProvider<KeyEncryptionProvider> cryptoProvider) {
        this.jdbc = jdbc;
        this.cryptoProvider = cryptoProvider;
    }

    /** One decrypted page of retention rows (newest first). */
    public List<AdminRetentionLogView> query(UUID tenantId, UUID userId, String direction, String protocol,
            Instant from, Instant to, int page, int size) {
        if (page < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "page must be >= 1");
        }
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        FilterSql filter = filter(tenantId, userId, direction, protocol, from, to);
        filter.params.addValue("limit", safeSize).addValue("offset", (long) (page - 1) * safeSize);
        List<RawRow> rows = jdbc.query("""
                SELECT r.event_id, r.user_id, u.username, u.display_name, r.virtual_key_id, r.wire_protocol,
                       r.direction, r.gateway_request_id, r.occurred_at, r.key_version, r.ciphertext, r.nonce,
                       r.text_char_count, r.truncated
                FROM retention_log r
                LEFT JOIN users u ON u.tenant_id = r.tenant_id AND u.id = r.user_id
                """ + filter.where + " ORDER BY r.occurred_at DESC, r.event_id LIMIT :limit OFFSET :offset",
                filter.params, ROW_MAPPER);
        List<AdminRetentionLogView> views = new ArrayList<>(rows.size());
        for (RawRow row : rows) {
            views.add(toView(tenantId, row));
        }
        return views;
    }

    /**
     * How many rows the export would cover (same filters). Answered by {@code count(*)}
     * so the caller can set the truncation header before any row is written — the
     * response is streamed, so headers are committed with the first byte.
     */
    public long countForExport(UUID tenantId, UUID userId, String direction, String protocol, Instant from,
            Instant to) {
        FilterSql filter = filter(tenantId, userId, direction, protocol, from, to);
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM retention_log r" + filter.where, filter.params, Long.class);
        return count == null ? 0L : count;
    }

    /**
     * CSV export (same filters), written row by row into {@code out} and capped at
     * {@link #EXPORT_LIMIT} rows.
     *
     * <p>Streaming is not an optimisation here, it is the fix (#1023): the previous
     * shape read up to EXPORT_LIMIT rows into a {@code List}, decrypted every one of
     * them and concatenated the whole document into a {@code StringBuilder} — at
     * 20 KB of ciphertext per row (this deployment's average) that is hundreds of
     * megabytes of live objects before the first byte reaches the client, and the
     * control plane died of heap exhaustion instead of exporting. The cap is a row
     * count, which says nothing about memory; what bounds memory here is reading a
     * page at a time and writing each row out before asking for the next.
     */
    public ExportSummary streamCsv(UUID tenantId, UUID userId, String direction, String protocol, Instant from,
            Instant to, java.io.OutputStream out) throws java.io.IOException {
        java.io.Writer w = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));
        // UTF-8 BOM so spreadsheet consumers detect the encoding: api-contract §5.0
        // requires the audit/retention downloads to share this dialect.
        w.write('\uFEFF');
        w.write("event_id,occurred_at,user_id,user_name,direction,wire_protocol,gateway_request_id,"
                + "virtual_key_id,text_char_count,truncated,data_md5,content\n");

        int written = 0;
        Instant cursorAt = null;
        UUID cursorId = null;
        while (written < EXPORT_LIMIT) {
            int pageSize = Math.min(EXPORT_CHUNK, EXPORT_LIMIT - written);
            List<RawRow> rows = exportChunk(tenantId, userId, direction, protocol, from, to, cursorAt, cursorId,
                    pageSize);
            if (rows.isEmpty()) {
                break;
            }
            for (RawRow row : rows) {
                writeCsvRow(w, toView(tenantId, row));
            }
            written += rows.size();
            RawRow last = rows.get(rows.size() - 1);
            cursorAt = last.occurredAt();
            cursorId = last.eventId();
            if (rows.size() < pageSize) {
                break;
            }
        }
        w.flush();
        return new ExportSummary(written, written == EXPORT_LIMIT);
    }

    /** CSV export payload: how many rows were written, and whether the cap was hit. */
    public record ExportSummary(int rows, boolean truncated) {
    }

    /**
     * One page of the export, ordered the way the document is: {@code occurred_at DESC,
     * event_id ASC}. Keyset pagination (rather than OFFSET) so the work is linear and a
     * page never depends on rows already written.
     */
    private List<RawRow> exportChunk(UUID tenantId, UUID userId, String direction, String protocol, Instant from,
            Instant to, Instant cursorAt, UUID cursorId, int pageSize) {
        FilterSql filter = filter(tenantId, userId, direction, protocol, from, to);
        String cursor = "";
        if (cursorAt != null) {
            cursor = " AND (r.occurred_at < :cursorAt OR (r.occurred_at = :cursorAt AND r.event_id > :cursorId))";
            filter.params.addValue("cursorAt", Timestamp.from(cursorAt)).addValue("cursorId", cursorId);
        }
        filter.params.addValue("limit", pageSize);
        return jdbc.query("""
                SELECT r.event_id, r.user_id, u.username, u.display_name, r.virtual_key_id, r.wire_protocol,
                       r.direction, r.gateway_request_id, r.occurred_at, r.key_version, r.ciphertext, r.nonce,
                       r.text_char_count, r.truncated
                FROM retention_log r
                LEFT JOIN users u ON u.tenant_id = r.tenant_id AND u.id = r.user_id
                """ + filter.where + cursor + " ORDER BY r.occurred_at DESC, r.event_id LIMIT :limit", filter.params,
                ROW_MAPPER);
    }

    private static void writeCsvRow(java.io.Writer w, AdminRetentionLogView view) throws java.io.IOException {
        w.write(String.valueOf(view.eventId()));
        w.write(',');
        w.write(String.valueOf(view.occurredAt()));
        w.write(',');
        w.write(String.valueOf(view.userId()));
        w.write(',');
        w.write(csvCell(view.userName()));
        w.write(',');
        w.write(String.valueOf(view.direction()));
        w.write(',');
        w.write(String.valueOf(view.wireProtocol()));
        w.write(',');
        w.write(String.valueOf(view.gatewayRequestId()));
        w.write(',');
        w.write(String.valueOf(view.virtualKeyId()));
        w.write(',');
        w.write(String.valueOf(view.textCharCount()));
        w.write(',');
        w.write(String.valueOf(view.truncated()));
        w.write(',');
        w.write(view.dataMd5() == null ? "" : view.dataMd5());
        w.write(',');
        w.write(csvCell(view.text()));
        w.write('\n');
    }

    // ------------------------------------------------------------------

    private static final RowMapper<RawRow> ROW_MAPPER = (rs, i) -> new RawRow(rs.getObject("event_id", UUID.class),
            rs.getObject("user_id", UUID.class), rs.getString("username"), rs.getString("display_name"),
            rs.getObject("virtual_key_id", UUID.class), rs.getString("wire_protocol"), rs.getString("direction"),
            rs.getString("gateway_request_id"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("key_version"),
            rs.getBytes("ciphertext"), rs.getBytes("nonce"), rs.getInt("text_char_count"), rs.getBoolean("truncated"));

    /** Package-private for tests: built by the row mapper, consumed by toView. */
    record RawRow(UUID eventId, UUID userId, String username, String displayName, UUID virtualKeyId,
            String wireProtocol, String direction, String gatewayRequestId, Instant occurredAt, String keyVersion,
            byte[] ciphertext, byte[] nonce, int textCharCount, boolean truncated) {
    }

    private record FilterSql(String where, MapSqlParameterSource params) {
    }

    private static FilterSql filter(UUID tenantId, UUID userId, String direction, String protocol, Instant from,
            Instant to) {
        StringBuilder where = new StringBuilder(" WHERE r.tenant_id = :tenantId");
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
        if (userId != null) {
            where.append(" AND r.user_id = :userId");
            params.addValue("userId", userId);
        }
        if (direction != null && !direction.isBlank()) {
            String normalized = direction.trim().toUpperCase(Locale.ROOT);
            if (!"INPUT".equals(normalized) && !"OUTPUT".equals(normalized)) {
                // ApiException, not ResponseStatusException: the advice's catch-all
                // @ExceptionHandler(Exception.class) wins over the resolver that would
                // otherwise honour a ResponseStatusException's own status, so throwing
                // one here surfaces as 500 INTERNAL_ERROR instead of a client error.
                throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "direction must be INPUT or OUTPUT");
            }
            where.append(" AND r.direction = :direction");
            params.addValue("direction", normalized);
        }
        if (protocol != null && !protocol.isBlank()) {
            where.append(" AND r.wire_protocol = :protocol");
            params.addValue("protocol", protocol.trim().toUpperCase(Locale.ROOT));
        }
        if (from != null) {
            where.append(" AND r.occurred_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND r.occurred_at < :to");
            params.addValue("to", Timestamp.from(to));
        }
        return new FilterSql(where.toString(), params);
    }

    private AdminRetentionLogView toView(UUID tenantId, RawRow row) {
        String text = null;
        String md5 = null;
        KeyEncryptionProvider provider = cryptoProvider.getIfAvailable();
        if (provider != null) {
            try {
                byte[] plain = provider.decrypt(new EncryptedSecret(row.ciphertext(), row.nonce(), row.keyVersion()),
                        tenantId, RetentionEnvelope.AAD_ID);
                text = new String(plain, StandardCharsets.UTF_8);
                md5 = md5Hex(plain);
            } catch (Exception e) {
                // Retired key version / tampered row: expose the metadata, not a 500.
                text = null;
            }
        }
        String userName = row.displayName() != null && !row.displayName().isBlank()
                ? row.displayName()
                : row.username();
        return new AdminRetentionLogView(row.eventId(), row.userId(), userName, row.virtualKeyId(), row.wireProtocol(),
                row.direction(), row.gatewayRequestId(), row.occurredAt(), row.textCharCount(), row.truncated(), md5,
                text);
    }

    private static String md5Hex(byte[] plain) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(plain));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * RFC 4180 cell: quote when it contains comma/quote/newline; double quotes.
     * Spreadsheet formula-injection guard (#430) on top, as required of the
     * "synchronous admin download" dialect (api-contract §5.0 前段): a cell starting
     * with {@code = + - @ TAB CR} is executed as a formula by Excel/LibreOffice, so
     * it gains an apostrophe prefix (displayed text unchanged, execution
     * neutralized). Both guarded columns — {@code user_name} and the decrypted
     * {@code content} — carry caller-controlled text.
     */
    static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String guarded = value;
        if (!guarded.isEmpty() && "=+-@\t\r".indexOf(guarded.charAt(0)) >= 0) {
            guarded = "'" + guarded;
        }
        if (guarded.indexOf(',') < 0 && guarded.indexOf('"') < 0 && guarded.indexOf('\n') < 0
                && guarded.indexOf('\r') < 0) {
            return guarded;
        }
        return '"' + guarded.replace("\"", "\"\"") + '"';
    }
}
