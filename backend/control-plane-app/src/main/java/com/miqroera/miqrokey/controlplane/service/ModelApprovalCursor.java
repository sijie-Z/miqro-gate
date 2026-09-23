package com.miqroera.miqrokey.controlplane.service;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor for the admin model-approval queue (#1392):
 * {@code base64url("{createdAtEpochMicros}:{id}")}, newest-first.
 *
 * <p>
 * The cursor used to carry {@code createdAt.toEpochMilli()}. That is one
 * decimal digit too few for the column behind it:
 * {@code model_approval.created_at} is a {@code timestamptz}, PostgreSQL keeps
 * microseconds, and {@code ModelApprovalService} stamps rows with
 * {@code Instant.now()}. A sub-millisecond burst — two users submitting in the
 * same millisecond, which is exactly what the approval queue's traffic looks
 * like — therefore encoded a cursor that sorted <em>after</em> its siblings
 * instead of before them. The walk returned page 1, jumped over every row still
 * sitting inside that millisecond, and ended early: rows that plainly exist in
 * the queue were never handed out, and the frontend's "load more" had nothing
 * left to load.
 * </p>
 *
 * <p>
 * Microseconds are the finest unit the column can hold, so this is the lossless
 * encoding, not just a wider one.
 * </p>
 */
public record ModelApprovalCursor(Instant createdAt, UUID id) {

    /**
     * First page: no bound, so the repository's own ORDER BY/LIMIT decides.
     */
    public static ModelApprovalCursor start() {
        return new ModelApprovalCursor(null, null);
    }

    /** Absent or blank {@code before} is the first page, not an error. */
    public static ModelApprovalCursor of(String before) {
        return before == null || before.isBlank() ? start() : decode(before);
    }

    public static ModelApprovalCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int sep = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, sep));
            return new ModelApprovalCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L), UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            // Never echo the value back: it is client input and may be anything.
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "分页游标无效，请刷新列表后重试");
        }
    }

    public static String encode(Instant createdAt, UUID id) {
        long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }
}
