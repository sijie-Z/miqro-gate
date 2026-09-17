package com.miqroera.miqrokey.controlplane.dto;

import java.util.UUID;

/**
 * Request to append one usage adjustment (#709).
 *
 * <p>
 * The target is named by {@code gatewayRequestId} — the id an administrator can
 * actually copy out of the usage list — rather than by the internal
 * {@code usage_event} primary key.
 * </p>
 *
 * <p>
 * Two shapes are accepted:
 * </p>
 * <ul>
 * <li>a <b>correction</b>: supply at least one token delta</li>
 * <li>a <b>reversal</b>: supply {@code reversalOfId} to undo an earlier
 * adjustment. The deltas are then negated from the row being undone and any
 * deltas in this request are ignored, so a reversal can never disagree with
 * what it reverses.</li>
 * </ul>
 *
 * <p>
 * There is no amount field: the COST dimension exists in the schema but is not
 * open for writing yet.
 * </p>
 *
 * @param idempotencyKey
 *            optional. Supplying one makes the write safe to retry —
 *            resubmitting the same key returns the adjustment already recorded
 *            instead of booking the correction twice
 */
public record UsageAdjustmentRequest(String gatewayRequestId, Long inputTokensDelta, Long outputTokensDelta,
        Long cacheReadTokensDelta, Long cacheCreationTokensDelta, String reason, String reasonCode, UUID reversalOfId,
        String idempotencyKey) {
}
