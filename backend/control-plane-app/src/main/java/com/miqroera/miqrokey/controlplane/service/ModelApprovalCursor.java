package com.miqroera.miqrokey.controlplane.service;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset cursor for the admin model-approval queue: the last row handed out,
 * identified by its {@code (created_at, id)}. Opaque to clients.
 *
 * <p>
 * Encoded as {@code base64url("{epochMicros}:{id}")} — microseconds, not
 * milliseconds. {@code model_approval.created_at} is a PostgreSQL
 * {@code timestamptz} carrying microseconds, so a millisecond boundary rounds
 * it <em>down</em>, and the strict
 * {@code (created_at, id) < (:beforeCreatedAt, :beforeId)} of the page query
 * then skips every row whose {@code created_at} lies in
 * {@code (truncatedValue, trueValue)}. Those rows are never returned by any
 * page: the walk ends early and the queue silently loses items. Encoding
 * microseconds keeps the boundary exactly where the row is (#1392).
 * </p>
 *
 * <p>
 * Cursors written by an older build (milliseconds) still decode, so an upgrade
 * between two pages of a walk does not turn into a {@code 400 PARAM_INVALID}.
 * Nothing can restore precision that was already dropped at encode time: such a
 * cursor keeps its old lossy boundary for the one step it covers. The two
 * formats are told apart by magnitude — millisecond values are ~1.78e12 today
 * and microsecond values ~1.78e15, so {@link #MICROS_THRESHOLD} sits between
 * them with room on both sides (a millisecond stamp would have to be year 5138,
 * a microsecond stamp year 1973, for the reading to flip).
 * </p>
 */
public record ModelApprovalCursor(Instant createdAt, UUID id) {

    /**
     * Values below this are epoch milliseconds, at or above it epoch microseconds.
     */
    private static final long MICROS_THRESHOLD = 100_000_000_000_000L;

    public static ModelApprovalCursor start() {
        return new ModelApprovalCursor(null, null);
    }

    public static ModelApprovalCursor of(String before) {
        return before == null || before.isBlank() ? start() : decode(before);
    }

    public static ModelApprovalCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int sep = raw.indexOf(':');
            long boundary = Long.parseLong(raw.substring(0, sep));
            return new ModelApprovalCursor(toInstant(boundary), UUID.fromString(raw.substring(sep + 1)));
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

    private static Instant toInstant(long boundary) {
        // Floor division, so a pre-1970 boundary still lands on the intended
        // microsecond instead of rounding towards zero.
        return boundary < MICROS_THRESHOLD
                ? Instant.ofEpochMilli(boundary)
                : Instant.ofEpochSecond(Math.floorDiv(boundary, 1_000_000L),
                        Math.floorMod(boundary, 1_000_000L) * 1_000L);
    }
}
