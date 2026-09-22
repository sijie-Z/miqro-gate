package com.miqroera.miqrokey.controlplane.service.reconciliation;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BillLine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.LineError;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Parsed;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code provider_row_ref} is declared a plain string by
 * docs/bill-reconciliation-contract.md:30 but stored in a
 * {@code varchar(256)} column (V42__reconciliation_reports.sql:47). An over-long
 * value used to reach the INSERT untouched (#1439): one line killed the whole
 * report, so this class pins both halves of the contract - the row survives with
 * a bounded ref AND the truncation is visible as a line error. Synthetic fixtures
 * only.
 */
@DisplayName("canonical bill: an over-long provider_row_ref is bounded to the column, not fatal to the file")
class CanonicalBillRowRefWidthTest {

    private static final String T = "2026-09-01T08:00:00Z";

    private final CanonicalBillParser parser = new CanonicalBillParser(new ObjectMapper());

    private static String ref(int codePoints, String fill) {
        return fill.repeat(codePoints);
    }

    private static String line(int n, String ref) {
        return "{\"provider_request_id\":\"r" + n + "\",\"occurred_at\":\"" + T + "\",\"amount\":\"1.00\","
                + "\"currency\":\"USD\"" + (ref == null ? "" : ",\"provider_row_ref\":\"" + ref + "\"") + "}";
    }

    private static Report run(List<BillLine> bills) {
        return BillReconciliationEngine.reconcile(bills, List.of(), Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"));
    }

    @Test
    @DisplayName("300 characters: truncated to exactly 256, one line error, row kept and still counted")
    void overLongRefIsBoundedAndReported() {
        Parsed parsed = parser.parse(line(1, ref(300, "R")));

        assertThat(parsed.lines()).as("the row is kept - it still carries the amount and the match anchors").hasSize(1);
        assertThat(parsed.errors()).extracting(LineError::code).containsExactly("FIELD_TOO_LONG");
        assertThat(parsed.errors()).extracting(LineError::lineNumber).containsExactly(1);
        assertThat(parsed.errors().get(0).detail())
                .as("lengths only: the caller-supplied value itself must not be echoed")
                .contains("300")
                .doesNotContain("RRR");
        assertThat(parsed.lines().get(0).providerRowRef()).hasSize(256).isEqualTo(ref(256, "R"));

        // The row survives into the four-state accounting, so the amount gap cannot
        // silently lose it.
        Report report = run(parsed.lines());
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.unmatchedProvider()).isEqualTo(1);
        assertThat(report.rows().get(0).providerRowRef()).hasSize(256);
    }

    @Test
    @DisplayName("the bound is counted in code points: 256 astral characters fit, they are not halved at 128")
    void boundIsCountedInCodePoints() {
        String astral = ref(256, "🚀"); // U+1F680, one code point, two UTF-16 units
        assertThat(astral).hasSize(512);

        Parsed parsed = parser.parse(line(1, astral));

        assertThat(parsed.errors()).as("256 code points fits a 256-character column").isEmpty();
        assertThat(parsed.lines().get(0).providerRowRef()).isEqualTo(astral);
    }

    @Test
    @DisplayName("a split surrogate pair cannot survive truncation (would not encode as UTF-8)")
    void truncationNeverSplitsASurrogatePair() {
        // 255 ASCII units, then an astral character straddling the 256-unit mark.
        String ref = ref(255, "a") + "🚀" + ref(10, "b");

        Parsed parsed = parser.parse(line(1, ref));
        String bounded = parsed.lines().get(0).providerRowRef();

        assertThat(parsed.errors()).extracting(LineError::code).containsExactly("FIELD_TOO_LONG");
        assertThat(bounded.codePointCount(0, bounded.length())).isEqualTo(256);
        assertThat(new String(bounded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .as("a lone surrogate would round-trip to '?' - the pair must be kept whole or dropped whole")
                .isEqualTo(bounded);
    }

    @Test
    @DisplayName("exactly 256 characters is not over the bound - no line error, no truncation")
    void exactlyAtTheBoundIsAccepted() {
        Parsed parsed = parser.parse(line(1, ref(256, "R")));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.lines().get(0).providerRowRef()).isEqualTo(ref(256, "R"));
    }

    @Test
    @DisplayName("one over-long line does not mask the rest: 3 lines in, 3 rows out, 1 error")
    void onlyTheOffendingLineIsReported() {
        Parsed parsed = parser.parse(String.join("\n", line(1, "short"), line(2, ref(400, "X")), line(3, null)));

        assertThat(parsed.lines()).hasSize(3);
        assertThat(parsed.errors()).extracting(LineError::lineNumber).containsExactly(2);
        assertThat(parsed.errors()).extracting(LineError::code).containsExactly("FIELD_TOO_LONG");
        assertThat(run(parsed.lines()).total()).isEqualTo(3);
    }

    @Test
    @DisplayName("an absent or null provider_row_ref is still allowed (it is an optional field)")
    void absentRefIsNotAnError() {
        Parsed parsed = parser.parse(line(1, null) + "\n"
                + "{\"provider_request_id\":\"r2\",\"occurred_at\":\"" + T + "\",\"amount\":\"1.00\","
                + "\"currency\":\"USD\",\"provider_row_ref\":null}");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.lines()).extracting(BillLine::providerRowRef).containsExactly(null, null);
    }
}
