package com.miqroera.miqrokey.controlplane.service;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor for the usage record lists (#1368):
 * {@code base64url("{occurredAtEpochMicros}:{id}")} — the last row of the page
 * just returned, which is exactly the boundary the next page continues from.
 *
 * <p>
 * Same shape as the model-approval queue's cursor, with one difference that
 * matters: <b>microseconds, not milliseconds</b>. {@code occurred_at} is a
 * PostgreSQL {@code timestamptz}, so rows of the same second routinely differ
 * below the millisecond — and the boundary is inclusive-exclusive, so rounding
 * it to the millisecond would make the next page skip every row in the same
 * millisecond that sorts after the cursor row. The encoded unit is capped at the
 * column's own precision, so nothing is lost in either direction.
 * </p>
 *
 * <p>
 * Never exposed as anything but an opaque string: clients pass it back as
 * {@code before} and stop when it comes back null.
 * </p>
 */
record UsageRecordCursor(Instant occurredAt, UUID id) {

    /** First page: no boundary yet. */
    static UsageRecordCursor start() {
        return new UsageRecordCursor(null, null);
    }

    /** First page for a blank/absent {@code before}, decoded cursor otherwise. */
    static UsageRecordCursor of(String before) {
        return before == null || before.isBlank() ? start() : decode(before);
    }

    static UsageRecordCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int sep = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, sep));
            return new UsageRecordCursor(
                    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L),
                    UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            // Never echo the value back: it is client input and may be anything.
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "分页游标无效，请刷新列表后重试");
        }
    }

    static String encode(Instant occurredAt, UUID id) {
        long micros = occurredAt.getEpochSecond() * 1_000_000L + occurredAt.getNano() / 1_000L;
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }
}
