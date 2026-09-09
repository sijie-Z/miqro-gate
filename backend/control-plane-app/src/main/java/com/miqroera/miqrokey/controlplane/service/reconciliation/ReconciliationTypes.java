package com.miqroera.miqrokey.controlplane.service.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Shared records for bill reconciliation (F19 contract draft, 2026-09-09). Pure
 * data; the engine and parser live beside this file. Real provider parsers and
 * the fingerprint level are WAITING_FOR_SAMPLE - this surface is the canonical
 * contract only (docs/bill-reconciliation-contract.md).
 */
public final class ReconciliationTypes {

    /** One canonical bill row (JSONL object, camelCase). */
    public record BillLine(String providerRequestId, Instant occurredAt, String modelId, String providerProductCode,
            Long inputTokens, Long outputTokens, Long cacheReadTokens, String amount, String currency, String status,
            String providerRowRef) {
    }

    /** A local usage row (usage_event projection) used as the match target. */
    public record LocalUsageRow(String localRef, String providerRequestId, Instant occurredAt, String modelId,
            String providerProductCode, Long inputTokens, Long outputTokens, Long cacheReadTokens, boolean success) {
    }

    public enum Verdict {
        MATCHED, PARTIAL, UNMATCHED_PROVIDER, UNMATCHED_LOCAL
    }

    /** How a row matched (fingerprint+model+time level is deferred to sample). */
    public enum MatchLevel {
        REQUEST_ID, MODEL_TIME, TOKENS, NONE
    }

    /** Per-bill-row result; UNMATCHED_LOCAL rows are derived locally instead. */
    public record RowResult(String providerRowRef, Verdict verdict, MatchLevel level, String localRef) {
    }

    /**
     * Bucket-level PARTIAL: bill rows without ids share a product + 5-minute
     * bucket.
     */
    public record BucketDiff(String productCode, Instant bucketStart, int providerCount, int localCount) {
    }

    /**
     * Aggregated report. {@code amountDiff} is the sum of UNMATCHED_PROVIDER
     * amounts - the billed-but-unattributed gap (local rows carry no billed amount,
     * so this is the attribution gap, not a per-row delta).
     */
    public record Report(int total, int matched, int partial, int unmatchedProvider, int unmatchedLocal,
            BigDecimal amountDiff, List<RowResult> rows, List<BucketDiff> buckets) {
    }

    /** One malformed/missing-field canonical line (parser keeps going). */
    public record LineError(int lineNumber, String code, String detail) {
    }

    public record Parsed(List<BillLine> lines, List<LineError> errors) {
    }

    private ReconciliationTypes() {
    }
}
