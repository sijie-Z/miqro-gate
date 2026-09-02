package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.UsageDeletion;
import com.miqroera.miqrokey.domain.usage.UsageDeletionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Double-confirmed usage deletion (G4.4, {@code usage_deletions} V11): the
 * admin previews the window, creates a request (only the SHA-256 of the
 * one-time confirmation token is stored), and confirms with the token to make
 * the deletion permanent. The window is bounded like every other query; the
 * executed deletion is recorded in the audit chain. Records are removed
 * permanently — there is no soft-delete for usage.
 */
@Service
public class UsageDeletionService {

    private static final Logger LOG = LoggerFactory.getLogger(UsageDeletionService.class);

    private static final Duration MAX_WINDOW = Duration.ofDays(93);
    private static final Duration CONFIRMATION_TTL = Duration.ofHours(1);

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;

    public UsageDeletionService(NamedParameterJdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    /** Dry-run count of rows in the window. */
    public long preview(UUID tenantId, Instant from, Instant to) {
        validateWindow(from, to);
        Long count = jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM usage_event WHERE tenant_id = :tenantId
                          AND occurred_at >= :from AND occurred_at < :to
                        """, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("from", java.sql.Timestamp.from(from)).addValue("to", java.sql.Timestamp.from(to)),
                Long.class);
        return count != null ? count : 0;
    }

    /**
     * Creates a deletion request. The confirmation token is returned exactly once;
     * only its SHA-256 hash is persisted.
     */
    @Transactional
    public DeletionRequest create(UUID tenantId, UUID adminId, Instant from, Instant to) {
        validateWindow(from, to);
        long count = preview(tenantId, from, to);
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        byte[] tokenHash = sha256(token);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO usage_deletions
                    (id, tenant_id, requested_by, period_from, period_to, preview_count, confirm_token_hash, status,
                     expires_at, created_at)
                VALUES (:id, :tenantId, :requestedBy, :periodFrom, :periodTo, :previewCount, :tokenHash,
                        'PENDING_CONFIRMATION', :expiresAt, :createdAt)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("requestedBy", adminId)
                .addValue("periodFrom", java.sql.Timestamp.from(from)).addValue("periodTo", java.sql.Timestamp.from(to))
                .addValue("previewCount", count).addValue("tokenHash", tokenHash)
                .addValue("expiresAt", java.sql.Timestamp.from(now.plus(CONFIRMATION_TTL)))
                .addValue("createdAt", java.sql.Timestamp.from(now)));
        return new DeletionRequest(id, count, token, now.plus(CONFIRMATION_TTL));
    }

    /**
     * Confirms and executes the deletion. The token must match the stored hash;
     * expired or already executed requests are rejected. Deletes the window and
     * writes a permanent audit event (the audit chain itself is never deleted).
     */
    @Transactional
    public UsageDeletion confirm(UUID tenantId, UUID deletionId, String confirmToken) {
        UsageDeletion deletion = find(tenantId, deletionId);
        if (deletion.status() != UsageDeletionStatus.PENDING_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT, "DELETION_NOT_CONFIRMABLE",
                    "The deletion request is not awaiting confirmation");
        }
        if (deletion.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "DELETION_EXPIRED", "The confirmation window has expired");
        }
        if (!MessageDigest.isEqual(deletion.confirmTokenHash(), sha256(confirmToken))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DELETION_TOKEN_INVALID", "The confirmation token is invalid");
        }
        long deleted = jdbc.queryForObject("""
                WITH deleted AS (
                    DELETE FROM usage_event WHERE tenant_id = :tenantId
                      AND occurred_at >= :from AND occurred_at < :to
                    RETURNING 1
                )
                SELECT COUNT(*) FROM deleted
                """,
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("from", java.sql.Timestamp.from(deletion.periodFrom()))
                        .addValue("to", java.sql.Timestamp.from(deletion.periodTo())),
                Long.class);
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE usage_deletions
                SET status = 'EXECUTED', deleted_count = :deleted, executed_at = :executedAt
                WHERE id = :id
                """, new MapSqlParameterSource("deleted", deleted).addValue("executedAt", java.sql.Timestamp.from(now))
                .addValue("id", deletionId));
        auditService.record(tenantId, deletion.requestedBy(), "USAGE_DELETE", "DELETION", deletionId,
                "{\"deletionId\":\"" + deletionId + "\",\"from\":\"" + deletion.periodFrom() + "\",\"to\":\""
                        + deletion.periodTo() + "\",\"deletedCount\":" + deleted + "}",
                null);
        LOG.warn("Usage deletion executed: {} rows in {}..{} (request {}) by {}", deleted, deletion.periodFrom(),
                deletion.periodTo(), deletionId, deletion.requestedBy());
        return find(tenantId, deletionId);
    }

    /** Recent deletion requests (metadata only; never the token). */
    public List<UsageDeletion> recent(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT * FROM usage_deletions WHERE tenant_id = :tenantId
                ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("limit", Math.min(limit, 50)),
                ROW_MAPPER);
    }

    /** One-time response carrying the confirm token. */
    public record DeletionRequest(UUID id, long previewCount, String confirmToken, Instant expiresAt) {
    }

    // -------------------------------------------------------------------

    private UsageDeletion find(UUID tenantId, UUID deletionId) {
        List<UsageDeletion> found = jdbc.query("""
                SELECT * FROM usage_deletions WHERE id = :id AND tenant_id = :tenantId
                """, new MapSqlParameterSource("id", deletionId).addValue("tenantId", tenantId), ROW_MAPPER);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DELETION_NOT_FOUND",
                    "Deletion request not found or not visible");
        }
        return found.get(0);
    }

    private static void validateWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_INVALID", "from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_RANGE_TOO_WIDE",
                    "The deletion window must be at most " + MAX_WINDOW.toDays() + " days");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reclaims deletion requests whose 1h confirmation window passed (F06):
     * PENDING_CONFIRMATION / CONFIRMED / EXPIRED rows past {@code expires_at} are
     * removed. EXECUTED requests are kept permanently (permanent audit, G4.4).
     * Returns the number of removed requests.
     */
    @Transactional
    public int sweepExpired() {
        return jdbc.update("""
                DELETE FROM usage_deletions
                WHERE status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'EXPIRED')
                  AND expires_at < now()
                """, new MapSqlParameterSource());
    }

    private static final RowMapper<UsageDeletion> ROW_MAPPER = (rs, rowNum) -> new UsageDeletion(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("requested_by"),
            rs.getTimestamp("period_from").toInstant(), rs.getTimestamp("period_to").toInstant(),
            rs.getLong("preview_count"), rs.getBytes("confirm_token_hash"),
            UsageDeletionStatus.valueOf(rs.getString("status")), rs.getObject("deleted_count", Long.class),
            rs.getTimestamp("executed_at") != null ? rs.getTimestamp("executed_at").toInstant() : null,
            rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant());
}
