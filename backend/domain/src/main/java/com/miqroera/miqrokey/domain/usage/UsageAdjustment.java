package com.miqroera.miqrokey.domain.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only usage adjustment (#709 / backlog F20), the second layer of
 * the usage model:
 *
 * <ol>
 * <li>{@code usage_event} — observed facts; never updated, never implicitly
 * rewritten</li>
 * <li><b>{@code usage_adjustments}</b> — this record: corrections,
 * appended</li>
 * <li>{@code AdjustedUsage} = observed + Σ adjustments — the
 * financial/reporting reading, used by detail, aggregates, billing and
 * exports</li>
 * <li>quota enforcement keeps reading observed usage only: a financial
 * correction must not retroactively rewrite what the runtime already
 * decided</li>
 * </ol>
 *
 * <p>
 * Corrections are themselves corrected by appending a <em>reversal</em> row
 * that points at the row it undoes via {@code reversalOfId} — there is no
 * mutable status column, so this table is append-only too. Reversing a reversal
 * is rejected; record a fresh business correction instead.
 * </p>
 *
 * <p>
 * Token deltas are nullable and may be negative. For {@code USAGE} rows at
 * least one delta must be present and the money fields must be absent; for
 * {@code COST} rows it is the other way round. Both rules are enforced by the
 * table CHECK as well as by the service.
 * </p>
 *
 * @param usageEventId
 *            the observed fact this correction applies to
 * @param reconciliationRowId
 *            provenance: the reconciliation finding that prompted this
 *            correction. Deliberately <em>not</em> a foreign key —
 *            reconciliation reports are replaced idempotently per window, so
 *            the reference is a best-effort pointer that may dangle
 * @param reversalOfId
 *            when set, this row undoes the adjustment with that id
 * @param idempotencyKey
 *            optional natural key so a retried submission does not record the
 *            same correction twice
 */
public record UsageAdjustment(UUID id, UUID tenantId, UUID usageEventId, AdjustmentType adjustmentType,
        Long inputTokensDelta, Long outputTokensDelta, Long cacheReadTokensDelta, Long cacheCreationTokensDelta,
        BigDecimal amountDelta, String currencyCode, String reason, String reasonCode, UUID reconciliationRowId,
        UUID reversalOfId, UUID createdBy, Instant createdAt, String idempotencyKey) {

    public UsageAdjustment {
        if (id == null || tenantId == null || usageEventId == null || adjustmentType == null || createdAt == null) {
            throw new IllegalArgumentException("required fields must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        boolean anyTokenDelta = inputTokensDelta != null || outputTokensDelta != null || cacheReadTokensDelta != null
                || cacheCreationTokensDelta != null;
        if (adjustmentType == AdjustmentType.USAGE && (!anyTokenDelta || amountDelta != null)) {
            throw new IllegalArgumentException("USAGE adjustments carry token deltas and no amount");
        }
        if (adjustmentType == AdjustmentType.COST && (anyTokenDelta || amountDelta == null || currencyCode == null)) {
            throw new IllegalArgumentException("COST adjustments carry an amount and currency and no token deltas");
        }
        if (reversalOfId != null && reversalOfId.equals(id)) {
            throw new IllegalArgumentException("an adjustment cannot reverse itself");
        }
    }

    /**
     * True when this row undoes another adjustment rather than stating a new one.
     */
    public boolean isReversal() {
        return reversalOfId != null;
    }
}
