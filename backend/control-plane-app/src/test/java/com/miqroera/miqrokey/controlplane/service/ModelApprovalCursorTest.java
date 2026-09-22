package com.miqroera.miqrokey.controlplane.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ModelApprovalCursor}: the admin approval queue's keyset
 * boundary has to carry the whole {@code created_at}, microseconds included
 * (#1392).
 */
class ModelApprovalCursorTest {

    private static final UUID ID = UUID.fromString("a173c50a-40fe-417d-b343-879942b32f0e");
    private static final Instant BOUNDARY = Instant.parse("2026-06-01T00:00:00.000500Z");

    @Test
    void roundTripsMicrosecondPrecision() {
        ModelApprovalCursor decoded = ModelApprovalCursor.decode(ModelApprovalCursor.encode(BOUNDARY, ID));

        assertThat(decoded.createdAt()).isEqualTo(BOUNDARY);
        assertThat(decoded.id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("encoding keeps the sub-millisecond part that millis would drop (#1392)")
    void encodingKeepsTheSubMillisecondPartThatMillisWouldDrop() {
        Instant truncated = Instant.ofEpochMilli(BOUNDARY.toEpochMilli());

        ModelApprovalCursor decoded = ModelApprovalCursor.decode(ModelApprovalCursor.encode(BOUNDARY, ID));

        // The old cursor round-tripped through toEpochMilli()/ofEpochMilli() and
        // came back as `truncated` — half a millisecond above the row it named. The
        // page query's strict "<" against that boundary skips every row created in
        // between, so those rows are returned by no page at all.
        assertThat(decoded.createdAt()).isNotEqualTo(truncated);
        assertThat(decoded.createdAt()).isAfter(truncated);
    }

    @Test
    @DisplayName("a millisecond cursor written by an older build still decodes (#1392)")
    void millisecondCursorFromAnOlderBuildStillDecodes() {
        String legacy = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((BOUNDARY.toEpochMilli() + ":" + ID).getBytes(StandardCharsets.US_ASCII));

        ModelApprovalCursor decoded = ModelApprovalCursor.decode(legacy);

        // Still lossy — that precision was dropped when the string was written and
        // nothing here can bring it back — but it names the row instead of failing
        // a walk that was already under way when the gateway was upgraded.
        assertThat(decoded.createdAt()).isEqualTo(Instant.ofEpochMilli(BOUNDARY.toEpochMilli()));
        assertThat(decoded.id()).isEqualTo(ID);
    }

    @Test
    void encodedCursorIsUrlSafeWithoutPadding() {
        String encoded = ModelApprovalCursor.encode(BOUNDARY, ID);

        assertThat(encoded).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(Base64.getUrlDecoder().decode(encoded)).isNotEmpty();
    }

    @Test
    void absentOrBlankCursorStartsAtTheTop() {
        assertThat(ModelApprovalCursor.of(null).createdAt()).isNull();
        assertThat(ModelApprovalCursor.of("").createdAt()).isNull();
        assertThat(ModelApprovalCursor.of("   ").id()).isNull();
    }

    @Test
    void malformedCursorIsRejectedWithoutEchoingIt() {
        String evil = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-a-number:also-not-a-uuid".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> ModelApprovalCursor.decode(evil)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("PARAM_INVALID");
            assertThat(e.getMessage()).doesNotContain(evil);
        });
        assertThatThrownBy(() -> ModelApprovalCursor.decode("!!!not-base64!!!")).isInstanceOf(ApiException.class);
        // A well-formed number in front of something that is not a UUID has to be
        // rejected too — the separator is found, the id is not.
        assertThatThrownBy(() -> ModelApprovalCursor.decode(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("12345:not-a-uuid".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ModelApprovalCursor.decode(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nocolonatall".getBytes(StandardCharsets.US_ASCII)))).isInstanceOf(ApiException.class);
    }
}
