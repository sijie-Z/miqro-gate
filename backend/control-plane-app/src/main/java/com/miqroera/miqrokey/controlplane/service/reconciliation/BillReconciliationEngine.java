package com.miqroera.miqrokey.controlplane.service.reconciliation;

import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BillLine;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.BucketDiff;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.LocalUsageRow;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.MatchLevel;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Report;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.RowResult;
import com.miqroera.miqrokey.controlplane.service.reconciliation.ReconciliationTypes.Verdict;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Four-level bill matching engine (F19 contract draft v0, usage-accounting
 * §11): request ID → model+time (±60s, unique) → token equality (unique).
 * Fingerprint+model+time level needs real credential fingerprint resolution and
 * is WAITING_FOR_SAMPLE. Bill rows without ids are aggregated per (product,
 * 5-minute bucket) into PARTIAL bucket diffs; UNMATCHED_LOCAL rows come from
 * the local side (non-null request id only).
 *
 * <p>
 * Pure function over in-memory rows - no persistence, no usage_event writes.
 * Synthetic test fixtures are marked {@code synthetic}.
 * </p>
 */
public final class BillReconciliationEngine {

    static final Duration LEVEL2_WINDOW = Duration.ofSeconds(60);
    static final Duration LEVEL3_WINDOW = Duration.ofSeconds(300);
    static final Duration BUCKET_WINDOW = Duration.ofMinutes(5);

    private BillReconciliationEngine() {
    }

    public static Report reconcile(List<BillLine> bills, List<LocalUsageRow> locals, Instant windowFrom,
            Instant windowTo) {
        List<LocalUsageRow> windowed = locals.stream()
                .filter(r -> !r.occurredAt().isBefore(windowFrom) && !r.occurredAt().isAfter(windowTo)).toList();

        Set<String> consumedLocal = new HashSet<>();
        List<RowResult> rows = new ArrayList<>(bills.size());
        List<BucketDiff> buckets = new ArrayList<>();
        BigDecimal amountGap = BigDecimal.ZERO;

        for (BillLine bill : bills) {
            LocalUsageRow match = match(bill, windowed, consumedLocal);
            if (match == null) {
                rows.add(new RowResult(bill.providerRowRef(), Verdict.UNMATCHED_PROVIDER, MatchLevel.NONE, null));
                amountGap = amountGap.add(amount(bill.amount()));
                continue;
            }
            consumedLocal.add(match.localRef());
            rows.add(new RowResult(bill.providerRowRef(), Verdict.MATCHED, level(bill, match), match.localRef()));
        }

        // Local rows that should have appeared on the bill but did not (id-based only).
        int unmatchedLocal = 0;
        for (LocalUsageRow local : windowed) {
            if (local.providerRequestId() != null && !consumedLocal.contains(local.localRef())) {
                unmatchedLocal++;
            }
        }

        buckets.addAll(bucketDiff(bills, windowed, consumedLocal));

        int matched = count(rows, Verdict.MATCHED);
        int unmatchedProvider = count(rows, Verdict.UNMATCHED_PROVIDER);
        return new Report(bills.size(), matched, buckets.size(), unmatchedProvider, unmatchedLocal, amountGap, rows,
                buckets);
    }

    private static LocalUsageRow match(BillLine bill, List<LocalUsageRow> windowed, Set<String> consumedLocal) {
        // Level 1: exact upstream request id (unique per tenant in usage_event).
        if (bill.providerRequestId() != null) {
            for (LocalUsageRow local : windowed) {
                if (bill.providerRequestId().equals(local.providerRequestId())) {
                    return local;
                }
            }
        }
        // Level 2: model + occurred_at within ±60s, exactly one unconsumed candidate.
        if (bill.modelId() != null && bill.occurredAt() != null) {
            LocalUsageRow unique = null;
            int candidates = 0;
            for (LocalUsageRow local : windowed) {
                if (consumedLocal.contains(local.localRef()) || !local.success()
                        || !Objects.equals(local.modelId(), bill.modelId())) {
                    continue;
                }
                long delta = Math.abs(Duration.between(local.occurredAt(), bill.occurredAt()).toSeconds());
                if (delta <= LEVEL2_WINDOW.toSeconds()) {
                    candidates++;
                    unique = local;
                }
            }
            if (candidates == 1) {
                return unique;
            }
        }
        // Level 3: token triple equality within ±5m, exactly one unconsumed candidate.
        if (bill.occurredAt() != null) {
            LocalUsageRow unique = null;
            int candidates = 0;
            for (LocalUsageRow local : windowed) {
                if (consumedLocal.contains(local.localRef()) || !local.success()) {
                    continue;
                }
                if (!tokensEqual(bill, local)) {
                    continue;
                }
                long delta = Math.abs(Duration.between(local.occurredAt(), bill.occurredAt()).toSeconds());
                if (delta <= LEVEL3_WINDOW.toSeconds()) {
                    candidates++;
                    unique = local;
                }
            }
            if (candidates == 1) {
                return unique;
            }
        }
        return null;
    }

    private static boolean tokensEqual(BillLine bill, LocalUsageRow local) {
        if (bill.inputTokens() == null && bill.outputTokens() == null) {
            return false;
        }
        return Objects.equals(bill.inputTokens(), local.inputTokens())
                && Objects.equals(bill.outputTokens(), local.outputTokens())
                && Objects.equals(bill.cacheReadTokens(), local.cacheReadTokens());
    }

    private static MatchLevel level(BillLine bill, LocalUsageRow local) {
        if (bill.providerRequestId() != null && bill.providerRequestId().equals(local.providerRequestId())) {
            return MatchLevel.REQUEST_ID;
        }
        if (bill.modelId() != null && Objects.equals(bill.modelId(), local.modelId())) {
            return MatchLevel.MODEL_TIME;
        }
        return MatchLevel.TOKENS;
    }

    /**
     * Unmatched id-less bill rows vs unconsumed locals in the same 5-minute bucket.
     */
    private static List<BucketDiff> bucketDiff(List<BillLine> bills, List<LocalUsageRow> windowed,
            Set<String> consumedLocal) {
        Map<String, int[]> counts = new HashMap<>();
        for (BillLine bill : bills) {
            if (bill.providerRequestId() != null || bill.occurredAt() == null) {
                continue;
            }
            String key = bucketKey(bill.providerProductCode(), bill.occurredAt());
            counts.computeIfAbsent(key, k -> new int[2])[0]++;
        }
        for (LocalUsageRow local : windowed) {
            if (local.providerRequestId() != null || consumedLocal.contains(local.localRef())
                    || local.occurredAt() == null) {
                continue;
            }
            String key = bucketKey(local.providerProductCode(), local.occurredAt());
            counts.computeIfAbsent(key, k -> new int[2])[1]++;
        }
        List<BucketDiff> result = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            if (entry.getValue()[0] > 0 || entry.getValue()[1] > 0) {
                result.add(new BucketDiff(entry.getKey(), null, entry.getValue()[0], entry.getValue()[1]));
            }
        }
        return result;
    }

    private static String bucketKey(String productCode, Instant occurredAt) {
        long bucket = occurredAt.getEpochSecond() / BUCKET_WINDOW.toSeconds();
        return (productCode == null ? "?" : productCode) + "@" + bucket;
    }

    private static BigDecimal amount(String amount) {
        try {
            return amount == null ? BigDecimal.ZERO : new BigDecimal(amount);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static int count(List<RowResult> rows, Verdict verdict) {
        return (int) rows.stream().filter(r -> r.verdict() == verdict).count();
    }
}
