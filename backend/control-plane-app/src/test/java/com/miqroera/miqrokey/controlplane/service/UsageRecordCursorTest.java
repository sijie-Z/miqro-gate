package com.miqroera.miqrokey.controlplane.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary precision of the usage-record cursor (#1368). The list pages with
 * {@code (occurred_at, id) < (cursor.occurredAt, cursor.id)}, so the cursor has
 * to carry the boundary row's own {@code occurred_at} — a value the gateway
 * writes with microsecond resolution. A cursor that only carried milliseconds
 * would make the next page skip every row inside the boundary row's millisecond
 * that sorts after it.
 */
@DisplayName("Usage record cursor boundary precision")
class UsageRecordCursorTest {

    private static final UUID ID = UUID.fromString("a173c50a-40fe-417d-b343-879942b32f0e");
    /** Microsecond-resolution boundary with a non-zero sub-millisecond part. */
    private static final Instant BOUNDARY = Instant.parse("2026-06-01T00:00:00.000500Z");

    @Test
    @DisplayName("round-trips the boundary instant exactly, microseconds included")
    void roundTripsMicrosecondPrecision() {
        String encoded = UsageRecordCursor.encode(BOUNDARY, ID);

        UsageRecordCursor decoded = UsageRecordCursor.decode(encoded);

        assertThat(decoded.occurredAt()).isEqualTo(BOUNDARY);
        assertThat(decoded.occurredAt().getNano()).isEqualTo(500_000);
        assertThat(decoded.id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("encoding does not truncate to milliseconds (the #1368 skip)")
    void encodingKeepsTheSubMillisecondPartThatMillisWouldDrop() {
        Instant truncated = Instant.ofEpochMilli(BOUNDARY.toEpochMilli());
        assertThat(truncated).as("precondition: millisecond truncation moves this instant").isNotEqualTo(BOUNDARY);

        UsageRecordCursor decoded = UsageRecordCursor.decode(UsageRecordCursor.encode(BOUNDARY, ID));

        // The regression: a cursor carrying `truncated` would put the next page's
        // boundary *below* the row it was cut from, and every row in
        // (truncated, BOUNDARY) would become unreachable.
        assertThat(decoded.occurredAt()).isNotEqualTo(truncated);
        assertThat(decoded.occurredAt()).isAfter(truncated);
    }

    @Test
    @DisplayName("the cursor string stays opaque and URL-safe")
    void encodedCursorIsUrlSafeWithoutPadding() {
        String encoded = UsageRecordCursor.encode(BOUNDARY, ID);

        assertThat(encoded).doesNotContain("=").doesNotContain("+").doesNotContain("/").doesNotContain(":");
    }

    @Test
    @DisplayName("an absent or blank cursor means 'first page', not an error")
    void absentCursorStartsAtTheBeginning() {
        assertThat(UsageRecordCursor.of(null).occurredAt()).isNull();
        assertThat(UsageRecordCursor.of("   ").occurredAt()).isNull();
        assertThat(UsageRecordCursor.start().id()).isNull();
    }

    @Test
    @DisplayName("a malformed cursor is rejected without echoing it back")
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> UsageRecordCursor.decode("not-a-cursor!!")).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("PARAM_INVALID");
                    assertThat(e.getMessage()).doesNotContain("not-a-cursor!!");
                });
    }
}
