package com.miqroera.miqrokey.controlplane.service.reconciliation;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BillLine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.LocalUsageRow;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Report;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.RowResult;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Synthetic fixtures only - explicitly NOT provider data (F19 contract draft:
 * real parsers and acceptance wait for a real sample). Covers the parser error
 * matrix and the four-level engine matching matrix.
 */
@DisplayName("Bill reconciliation engine + canonical parser (synthetic fixtures)")
class BillReconciliationEngineTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    private static BillLine bill(String requestId, Instant at, String model, String product, Long in, Long out,
            String amount) {
        return new BillLine(requestId, at, model, product, in, out, null, amount, "USD", "success", null);
    }

    private static LocalUsageRow local(String ref, String requestId, Instant at, String model, String product, Long in,
            Long out) {
        return new LocalUsageRow(ref, requestId, at, model, product, in, out, null, true);
    }

    private static Report run(List<BillLine> bills, List<LocalUsageRow> locals) {
        return BillReconciliationEngine.reconcile(bills, locals, T0.minusSeconds(3600), T0.plusSeconds(3600));
    }

    // ------------------------------------------------------------------
    // parser
    // ------------------------------------------------------------------

    @Test
    @DisplayName("parser accepts a canonical line and collects per-line errors without failing the file")
    void parserMatrix() {
        CanonicalBillParser parser = new CanonicalBillParser(new ObjectMapper());
        String content = """
                {"provider_request_id":"req-1","occurred_at":"2026-09-01T08:00:00Z","model_id":"m1","input_tokens":10,"output_tokens":5,"amount":"0.001","currency":"USD"}
                not json
                {"occurred_at":"2026-09-01T08:00:00Z","amount":"0.002","currency":"USD"}
                """;
        ReconciliationTypes.Parsed parsed = parser.parse(content);
        assertThat(parsed.lines()).hasSize(2);
        assertThat(parsed.lines().get(0).providerRequestId()).isEqualTo("req-1");
        assertThat(parsed.errors()).hasSize(2).anySatisfy(e -> {
            assertThat(e.lineNumber()).isEqualTo(2);
            assertThat(e.code()).isEqualTo("LINE_NOT_JSON");
        }).anySatisfy(e -> assertThat(e.code()).isEqualTo("FIELD_REQUIRED"));
    }

    @Test
    @DisplayName("parser rejects a line with no matching anchor")
    void parserRequiresAnchor() {
        CanonicalBillParser parser = new CanonicalBillParser(new ObjectMapper());
        ReconciliationTypes.Parsed parsed = parser
                .parse("{\"occurred_at\":\"2026-09-01T08:00:00Z\",\"amount\":\"0.01\",\"currency\":\"USD\"}");
        assertThat(parsed.errors()).anySatisfy(e -> assertThat(e.code()).isEqualTo("FIELD_REQUIRED"));
    }

    // ------------------------------------------------------------------
    // engine
    // ------------------------------------------------------------------

    @Test
    @DisplayName("level 1: exact request id matches and nothing else is consumed")
    void level1RequestId() {
        BillLine bill = bill("req-1", T0, "m1", "p1", 10L, 5L, "0.001");
        LocalUsageRow hit = local("l1", "req-1", T0, "m1", "p1", 10L, 5L);
        LocalUsageRow other = local("l2", "req-2", T0.plusSeconds(5), "m1", "p1", 10L, 5L);

        Report report = run(List.of(bill), List.of(hit, other));
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.rows().get(0).level().name()).isEqualTo("REQUEST_ID");
        assertThat(report.rows().get(0).localRef()).isEqualTo("l1");
        assertThat(report.unmatchedLocal()).isEqualTo(1); // req-2 billed elsewhere
    }

    @Test
    @DisplayName("level 2: model + time ±60s with a single candidate matches")
    void level2ModelTime() {
        BillLine bill = bill(null, T0, "m1", "p1", 10L, 5L, "0.001");
        LocalUsageRow near = local("l1", "local-req-a", T0.plusSeconds(20), "m1", "p1", 10L, 5L);
        LocalUsageRow far = local("l2", "local-req-b", T0.plusSeconds(300), "m2", "p1", 10L, 5L);

        Report report = run(List.of(bill), List.of(near, far));
        assertThat(report.rows().get(0).level().name()).isEqualTo("MODEL_TIME");
        assertThat(report.rows().get(0).localRef()).isEqualTo("l1");
    }

    @Test
    @DisplayName("level 2 stays unmatched when two candidates are inside the window (uniqueness gate)")
    void level2RequiresUniqueCandidate() {
        BillLine bill = bill(null, T0, "m1", "p1", 10L, 5L, "0.001");
        List<LocalUsageRow> locals = List.of(local("l1", "a", T0, "m1", "p1", 10L, 5L),
                local("l2", "b", T0.plusSeconds(10), "m1", "p1", 10L, 5L));

        Report report = run(List.of(bill), locals);
        assertThat(report.unmatchedProvider()).isEqualTo(1);
    }

    @Test
    @DisplayName("level 3: identical tokens within ±5m with a single candidate matches")
    void level3Tokens() {
        BillLine bill = bill(null, T0, null, "p1", 10L, 5L, "0.001");
        LocalUsageRow near = local("l1", "x", T0.plusSeconds(120), null, "p1", 10L, 5L);
        LocalUsageRow different = local("l2", "y", T0.plusSeconds(130), null, "p1", 9L, 5L);

        Report report = run(List.of(bill), List.of(near, different));
        assertThat(report.rows().get(0).level().name()).isEqualTo("TOKENS");
        assertThat(report.rows().get(0).localRef()).isEqualTo("l1");
    }

    @Test
    @DisplayName("level 3: a bill without cache_read_tokens compares on input/output only (#625)")
    void level3NullCacheComparesInputOutput() {
        // The bill omits cache_read_tokens entirely (null). Before #625 the
        // engine compared null against the local 0 and could never match, so
        // cache-less provider rows always landed UNMATCHED_PROVIDER.
        BillLine bill = bill(null, T0, "m1", "p1", 50L, 43L, "0.001");
        LocalUsageRow hit = new LocalUsageRow("l1", null, T0.plusSeconds(5), "m1", "p1", 50L, 43L, 0L, true);
        LocalUsageRow other = new LocalUsageRow("l2", null, T0.plusSeconds(10), "m1", "p1", 50L, 24L, 0L, true);

        // Two same-model locals inside ±60s defeat the level-2 uniqueness gate;
        // the cache-less bill row must still match via level 3 on input/output.
        Report report = run(List.of(bill), List.of(hit, other));
        assertThat(report.rows().get(0).verdict().name()).isEqualTo("MATCHED");
        assertThat(report.rows().get(0).localRef()).isEqualTo("l1");
    }

    @Test
    @DisplayName("level 3: a bill that lists cache_read_tokens still requires an exact cache match (#625)")
    void level3CacheStillExact() {
        BillLine bill = new BillLine(null, T0, "m1", "p1", 50L, 43L, 7L, "0.001", "USD", "success", null);
        LocalUsageRow zeroCache = new LocalUsageRow("l1", null, T0.plusSeconds(5), "m1", "p1", 50L, 43L, 0L, true);
        LocalUsageRow other = new LocalUsageRow("l2", null, T0.plusSeconds(10), "m1", "p1", 50L, 24L, 0L, true);

        Report report = run(List.of(bill), List.of(zeroCache, other));
        assertThat(report.rows().get(0).verdict().name()).isEqualTo("UNMATCHED_PROVIDER");
    }

    @Test
    @DisplayName("unmatched provider rows accumulate the attribution gap as amountDiff")
    void amountGap() {
        BillLine bill = bill("ghost", T0, "m1", "p1", 10L, 5L, "0.0042");
        Report report = run(List.of(bill), List.of());
        assertThat(report.unmatchedProvider()).isEqualTo(1);
        assertThat(report.amountDiff()).isEqualByComparingTo("0.0042");
    }

    @Test
    @DisplayName("id-less bill rows land in PARTIAL bucket diffs against local rows")
    void partialBuckets() {
        BillLine a = bill(null, T0, "m1", "p1", 10L, 5L, "0.001");
        BillLine b = bill(null, T0.plusSeconds(90), "m1", "p1", 20L, 8L, "0.002");
        LocalUsageRow lone = local("l1", null, T0.plusSeconds(45), "other-model", "p1", 33L, 1L);

        Report report = run(List.of(a, b), List.of(lone));
        assertThat(report.partial()).isEqualTo(1);
        assertThat(report.buckets().get(0).providerCount()).isEqualTo(2);
        assertThat(report.buckets().get(0).localCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("verdicts are exclusive: matched rows never count as unmatched local")
    void noDoubleCounting() {
        BillLine bill = bill("req-1", T0, "m1", "p1", 10L, 5L, "0.001");
        LocalUsageRow hit = local("l1", "req-1", T0, "m1", "p1", 10L, 5L);

        Report report = run(List.of(bill), List.of(hit));
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.unmatchedProvider()).isZero();
        assertThat(report.unmatchedLocal()).isZero();
    }

    @Test
    @DisplayName("the window is half-open [from, to): the instant at `to` belongs to the next report")
    void windowUpperBoundIsExclusive() {
        Instant from = T0.minusSeconds(3600);
        Instant to = T0.plusSeconds(3600);
        List<LocalUsageRow> locals = List.of(local("l-at-from", "req-at-from", from, "m1", "p1", 10L, 5L),
                local("l-inside", "req-inside", to.minusSeconds(1), "m1", "p1", 10L, 5L),
                local("l-at-to", "req-at-to", to, "m1", "p1", 10L, 5L));

        Report report = BillReconciliationEngine.reconcile(List.of(), locals, from, to);

        // `from` inclusive, `to` exclusive — the convention every other usage
        // window in the product uses (stats, export, retention, quota periods).
        // A row exactly at `to` is also inside the NEXT window, so counting it
        // here as well charges it twice and makes this report's local side
        // disagree with the usage export for the same nominal window.
        assertThat(report.unmatchedLocalRows()).extracting(RowResult::localRef).containsExactlyInAnyOrder("l-at-from",
                "l-inside");
    }

    @Test
    @DisplayName("failed local requests never satisfy level 2 or 3")
    void failedLocalsSkipped() {
        BillLine bill = bill(null, T0, "m1", "p1", 10L, 5L, "0.001");
        LocalUsageRow failed = new LocalUsageRow("l1", "local-req", T0.plusSeconds(10), "m1", "p1", 10L, 5L, null,
                false);

        Report report = run(List.of(bill), List.of(failed));
        assertThat(report.unmatchedProvider()).isEqualTo(1);
        assertThat(report.rows().get(0).verdict()).isEqualTo(Verdict.UNMATCHED_PROVIDER);
    }
}
