package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.usage.ExportFormat;
import com.miqroera.miqrokey.domain.usage.ExportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata-only view of an export task (G4.4) for the open admin API — the
 * artifact bytes and their base64 serialization stay off the JSON surface;
 * downloads remain the session endpoint's job ({@code /api/v1/admin/exports}).
 */
public record ExportTaskView(UUID id, UUID createdBy, ExportFormat format, Instant periodFrom, Instant periodTo,
        ExportStatus status, String sha256, Long rowCount, Long byteCount, String errorMessage, Instant createdAt,
        Instant finishedAt, Instant expiresAt) {
}
