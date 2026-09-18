package com.miqroera.miqrokey.domain.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin export task (G4.4, {@code export_tasks} V11): an async CSV/JSONL gzip
 * export of raw usage records. The artifact bytes are stored with their
 * SHA-256, row count and byte count so downloads can be verified; the window is
 * bounded and records never contain prompt/code/secret content.
 *
 * <p>
 * {@code reconcileLevel} (V41, #330, usage-accounting §11): task-level
 * declaration of provider-request-id coverage — {@code PROVIDER_ID_BACKED}
 * (every row reconcilable by request ID), {@code PARTIAL} (mixed) or
 * {@code LOCAL_ONLY} (none); null for empty windows and historical tasks.
 * </p>
 *
 * <p>
 * {@code adjustmentLevel} (V67, #716): the other axis V41 left room for —
 * whether the numbers in the file include corrections. {@code PRESENT} (at
 * least one row carries one, so the {@code net*} columns are the ones to
 * reconcile with) or {@code NONE} (no row was ever corrected, so they simply
 * repeat the observed counts); null for empty windows and historical tasks. It
 * is stated per task, not only per row, because it is what a consumer wants to
 * know before reading the file.
 * </p>
 */
public record ExportTask(UUID id, UUID tenantId, UUID createdBy, ExportFormat format, Instant periodFrom,
        Instant periodTo, ExportStatus status, String sha256, Long rowCount, Long byteCount, byte[] fileBytes,
        String errorMessage, Instant createdAt, Instant finishedAt, Instant expiresAt, String reconcileLevel,
        String adjustmentLevel) {

    /**
     * Backwards-compatible constructor for a freshly created task: neither level is
     * known until the rows have been read.
     */
    public ExportTask(UUID id, UUID tenantId, UUID createdBy, ExportFormat format, Instant periodFrom, Instant periodTo,
            ExportStatus status, String sha256, Long rowCount, Long byteCount, byte[] fileBytes, String errorMessage,
            Instant createdAt, Instant finishedAt, Instant expiresAt) {
        this(id, tenantId, createdBy, format, periodFrom, periodTo, status, sha256, rowCount, byteCount, fileBytes,
                errorMessage, createdAt, finishedAt, expiresAt, null, null);
    }

    public ExportTask {
        if (id == null || tenantId == null || createdBy == null || format == null || periodFrom == null
                || periodTo == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("required fields must not be null");
        }
    }
}
