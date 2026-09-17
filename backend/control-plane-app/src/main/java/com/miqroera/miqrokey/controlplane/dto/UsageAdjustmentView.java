package com.miqroera.miqrokey.controlplane.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One usage adjustment as shown to an administrator (#709).
 *
 * <p>
 * Deltas are signed and may be negative. {@code reversalOfId} is set when the
 * row undoes an earlier adjustment — the ledger keeps both rows, so an audit
 * can replay how a correction was itself corrected. The money fields stay null
 * until the COST dimension is opened for writing.
 * </p>
 */
public record UsageAdjustmentView(UUID id, UUID usageEventId, String adjustmentType, Long inputTokensDelta,
        Long outputTokensDelta, Long cacheReadTokensDelta, Long cacheCreationTokensDelta, BigDecimal amountDelta,
        String currencyCode, String reason, String reasonCode, UUID reconciliationRowId, UUID reversalOfId,
        UUID createdBy, Instant createdAt) {
}
