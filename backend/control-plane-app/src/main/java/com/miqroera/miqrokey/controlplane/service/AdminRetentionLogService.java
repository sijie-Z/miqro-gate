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
import org.springframework.web.server.ResponseStatusException;

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
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        FilterSql filter = filter(tenantId, userId, direction, protocol, from, to);
        filter.params.addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
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

    /** CSV export (same filters); capped at {@link #EXPORT_LIMIT} rows. */
    public ExportResult exportCsv(UUID tenantId, UUID userId, String direction, String protocol, Instant from,
            Instant to) {
        FilterSql filter = filter(tenantId, userId, direction, protocol, from, to);
        filter.params.addValue("limit", EXPORT_LIMIT + 1);
        List<RawRow> rows = jdbc.query("""
                SELECT r.event_id, r.user_id, u.username, u.display_name, r.virtual_key_id, r.wire_protocol,
                       r.direction, r.gateway_request_id, r.occurred_at, r.key_version, r.ciphertext, r.nonce,
                       r.text_char_count, r.truncated
                FROM retention_log r
                LEFT JOIN users u ON u.tenant_id = r.tenant_id AND u.id = r.user_id
                """ + filter.where + " ORDER BY r.occurred_at DESC, r.event_id LIMIT :limit", filter.params,
                ROW_MAPPER);
        boolean truncated = rows.size() > EXPORT_LIMIT;
        if (truncated) {
            rows = rows.subList(0, EXPORT_LIMIT);
        }
        StringBuilder csv = new StringBuilder(
                "event_id,occurred_at,user_id,user_name,direction,wire_protocol,gateway_request_id,"
                        + "virtual_key_id,text_char_count,truncated,data_md5,content\n");
        // UTF-8 BOM so spreadsheet consumers detect the encoding: api-contract §5.0
        // requires the audit/retention downloads to share this dialect.
        csv.insert(0, '\uFEFF');
        for (RawRow row : rows) {
            AdminRetentionLogView view = toView(tenantId, row);
            csv.append(view.eventId()).append(',').append(view.occurredAt()).append(',').append(view.userId())
                    .append(',').append(csvCell(view.userName())).append(',').append(view.direction()).append(',')
                    .append(view.wireProtocol()).append(',').append(view.gatewayRequestId()).append(',')
                    .append(view.virtualKeyId()).append(',').append(view.textCharCount()).append(',')
                    .append(view.truncated()).append(',').append(view.dataMd5() == null ? "" : view.dataMd5())
                    .append(',').append(csvCell(view.text())).append('\n');
        }
        return new ExportResult(csv.toString(), rows.size(), truncated);
    }

    /** CSV export payload: rendered rows, the row count, and the cap flag. */
    public record ExportResult(String csv, int rows, boolean truncated) {
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be INPUT or OUTPUT");
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
