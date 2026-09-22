package com.miqroera.miqrokey.controlplane.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary precision of the model-approval cursor (#1392). The queue pages with
 * {@code (created_at, id) < (cursor.createdAt, cursor.id)}, so the cursor has
 * to carry the boundary row's own {@code created_at} — a {@code timestamptz}
 * the service stamps with {@code Instant.now()}. A cursor that only carried
 * milliseconds would make the next page skip every row inside the boundary
 * row's millisecond that sorts after it.
 */
@DisplayName("Model approval cursor boundary precision")
class ModelApprovalCursorTest {

    private static final UUID ID = UUID.fromString("25212d79-ca69-43be-a4fb-69c0f08db165");
    /** Microsecond-resolution boundary with a non-zero sub-millisecond part. */
    private static final Instant BOUNDARY = Instant.parse("2026-06-01T00:00:00.000500Z");

    @Test
    @DisplayName("round-trips the boundary instant exactly, microseconds included")
    void roundTripsMicrosecondPrecision() {
        String encoded = ModelApprovalCursor.encode(BOUNDARY, ID);

        ModelApprovalCursor decoded = ModelApprovalCursor.decode(encoded);

        assertThat(decoded.createdAt()).isEqualTo(BOUNDARY);
        assertThat(decoded.createdAt().getNano()).isEqualTo(500_000);
        assertThat(decoded.id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("encoding does not truncate to milliseconds (the #1392 skip)")
    void encodingKeepsTheSubMillisecondPartThatMillisWouldDrop() {
        Instant truncated = Instant.ofEpochMilli(BOUNDARY.toEpochMilli());
        assertThat(truncated).as("precondition: millisecond truncation moves this instant").isNotEqualTo(BOUNDARY);

        ModelApprovalCursor decoded = ModelApprovalCursor.decode(ModelApprovalCursor.encode(BOUNDARY, ID));

        // The regression: a cursor carrying `truncated` would put the next page's
        // boundary *below* the row it was cut from, and every row in
        // (truncated, BOUNDARY) would become unreachable.
        assertThat(decoded.createdAt()).isNotEqualTo(truncated);
        assertThat(decoded.createdAt()).isAfter(truncated);
    }

    @Test
    @DisplayName("the cursor string stays opaque and URL-safe")
    void encodedCursorIsUrlSafeWithoutPadding() {
        String encoded = ModelApprovalCursor.encode(BOUNDARY, ID);

        assertThat(encoded).doesNotContain("=").doesNotContain("+").doesNotContain("/").doesNotContain(":");
    }

    @Test
    @DisplayName("an absent or blank cursor means 'first page', not an error")
    void absentCursorStartsAtTheBeginning() {
        assertThat(ModelApprovalCursor.of(null).createdAt()).isNull();
        assertThat(ModelApprovalCursor.of("   ").createdAt()).isNull();
        assertThat(ModelApprovalCursor.start().id()).isNull();
    }

    @Test
    @DisplayName("a malformed cursor is rejected without echoing it back")
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> ModelApprovalCursor.decode("not-a-cursor!!"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("PARAM_INVALID");
                    assertThat(e.getMessage()).doesNotContain("not-a-cursor!!");
                });
    }
}
