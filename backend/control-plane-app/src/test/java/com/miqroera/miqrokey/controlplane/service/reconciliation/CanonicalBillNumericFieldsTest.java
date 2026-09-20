package com.miqroera.miqrokey.controlplane.service.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BillLine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.LineError;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Parsed;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonical bill numeric fields are declared "decimal string" / "int" by
 * docs/bill-reconciliation-contract.md, and the parser is documented as a
 * "strict JSONL parser". A value that does not satisfy the declared type must
 * therefore be reported as a line error - it must not be coerced to a different
 * value behind the operator's back. Synthetic fixtures only.
 */
@DisplayName("canonical bill: numeric fields are validated, never silently coerced")
class CanonicalBillNumericFieldsTest {

    private static final String T = "2026-09-01T08:00:00Z";

    private final CanonicalBillParser parser = new CanonicalBillParser(new ObjectMapper());

    private static Report run(List<BillLine> bills) {
        return BillReconciliationEngine.reconcile(bills, List.of(), Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"));
    }

    @Test
    @DisplayName("an amount that is not a decimal string is a line error (thousands separator / text / boolean)")
    void nonDecimalAmountIsReported() {
        Parsed parsed = parser.parse("""
                {"provider_request_id":"r1","occurred_at":"%s","amount":"1,234.00","currency":"USD"}
                {"provider_request_id":"r2","occurred_at":"%s","amount":"N/A","currency":"USD"}
                {"provider_request_id":"r3","occurred_at":"%s","amount":true,"currency":"USD"}
                """.formatted(T, T, T));

        assertThat(parsed.errors()).as("line errors, raw=%s", parsed.errors()).hasSize(3);
        assertThat(parsed.errors()).extracting(LineError::lineNumber).containsExactly(1, 2, 3);
        assertThat(parsed.errors()).extracting(LineError::code).containsExactly("FIELD_TYPE", "FIELD_TYPE",
                "FIELD_TYPE");
    }

    @Test
    @DisplayName("a token count that is not an integer is a line error, not a silent null")
    void nonIntegralTokensAreReported() {
        Parsed parsed = parser
                .parse("""
                        {"provider_request_id":"r1","occurred_at":"%s","input_tokens":"1000","output_tokens":5,"amount":"1.00","currency":"USD"}
                        {"provider_request_id":"r2","occurred_at":"%s","input_tokens":1000.0,"output_tokens":5,"amount":"1.00","currency":"USD"}
                        """
                        .formatted(T, T));

        assertThat(parsed.errors()).as("line errors, raw=%s", parsed.errors()).hasSize(2);
        assertThat(parsed.errors()).extracting(LineError::lineNumber).containsExactly(1, 2);
    }

    @Test
    @DisplayName("the declared decimal grammar still accepts every shape the export uses")
    void validNumbersStillParse() {
        Parsed parsed = parser
                .parse("""
                        {"provider_request_id":"r1","occurred_at":"%s","input_tokens":1000,"output_tokens":5,"cache_read_tokens":0,"amount":"-12.34","currency":"USD"}
                        {"provider_request_id":"r2","occurred_at":"%s","amount":"+3.00","currency":"USD"}
                        {"provider_request_id":"r3","occurred_at":"%s","amount":"0.00050000","currency":"USD"}
                        {"provider_request_id":"r4","occurred_at":"%s","amount":1.5,"currency":"USD"}
                        """
                        .formatted(T, T, T, T));

        assertThat(parsed.errors()).as("line errors, raw=%s", parsed.errors()).isEmpty();
        assertThat(parsed.lines()).hasSize(4);
        assertThat(parsed.lines()).extracting(BillLine::amount).containsExactly("-12.34", "+3.00", "0.00050000", "1.5");
    }

    @Test
    @DisplayName("an unparsable amount contributes 0 to the bill-only gap, so the gap is silent without the line error")
    void unparsableAmountIsCountedAsZeroInTheGap() {
        Report report = run(List.of(
                new BillLine("r1", Instant.parse(T), "m1", null, null, null, null, "1,234.00", "USD", null, "bill-1"),
                new BillLine("r2", Instant.parse(T), "m2", null, null, null, null, "2.00", "USD", null, "bill-2")));

        assertThat(report.unmatchedProvider()).isEqualTo(2);
        assertThat(report.amountDiff()).as("raw amountDiff for ['1,234.00', '2.00']").isEqualByComparingTo("2.00");
    }
}
