package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.usage.AdjustmentTarget;
import com.miqroera.miqrokey.domain.usage.UsageAdjustment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The append-only usage-adjustment ledger (#709 / backlog F20).
 *
 * <p>
 * Corrections to observed usage are recorded as new rows; the observed
 * {@code usage_event} row is never touched. There is deliberately no update or
 * delete operation here — undoing a correction is itself an append (a reversal
 * row), so the ledger stays append-only all the way down.
 * </p>
 *
 * <p>
 * Every lookup is tenant-scoped: an id belonging to another tenant must be
 * indistinguishable from one that does not exist.
 * </p>
 */
public interface UsageAdjustmentRepository {

    /**
     * Appends one adjustment, or returns the row already recorded under the same
     * idempotency key.
     *
     * <p>
     * Returning the effective row rather than signalling a conflict keeps the retry
     * path race-free without exception control flow: a resubmitted request and a
     * genuinely new one look the same to the caller.
     * </p>
     *
     * @return the appended row, or the pre-existing row when the key was already
     *         used
     */
    UsageAdjustment append(UsageAdjustment adjustment);

    /** One adjustment by id, or empty when unknown in this tenant. */
    Optional<UsageAdjustment> findById(UUID tenantId, UUID adjustmentId);

    /**
     * Idempotency lookup for a retried submission.
     *
     * @return the adjustment already recorded under this key, or empty
     */
    Optional<UsageAdjustment> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** Every adjustment recorded against one usage event, oldest first. */
    List<UsageAdjustment> findByUsageEventId(UUID tenantId, UUID usageEventId);

    /**
     * Resolves the usage event a correction should target, by the request id an
     * administrator actually sees in the usage list.
     *
     * <p>
     * The usage list exposes {@code gatewayRequestId}, not the {@code usage_event}
     * primary key, so the API accepts what the operator can copy. A request id can
     * match several rows (a coalesced follower shares its leader's id), so this
     * picks the UPSTREAM row — the one that carries observed usage — and falls back
     * to the most recent match.
     * </p>
     *
     * @return the usage event id, or empty when unknown in this tenant
     */
    Optional<UUID> findUsageEventIdByGatewayRequestId(UUID tenantId, String gatewayRequestId);

    /**
     * The validation view of one usage event: its observed token counts plus the
     * deltas already recorded against it.
     *
     * @return empty when the event is unknown in this tenant — which is also how a
     *         cross-tenant id presents itself
     */
    Optional<AdjustmentTarget> findAdjustmentTarget(UUID tenantId, UUID usageEventId);

    /**
     * Whether an adjustment has already been cancelled by a reversal row.
     *
     * <p>
     * One original carries at most one reversal. A reversal negates the deltas of
     * the row it points at, while every read path nets an event as
     * {@code observed + SUM(all deltas)}: booking the same reversal a second time
     * does not undo twice, it subtracts a correction that is already gone and
     * leaves the net <em>above</em> the observed fact by the size of the original.
     * </p>
     *
     * <p>
     * Read-then-write, so callers must hold the per-event lock (see
     * {@link #lockUsageEvent(UUID)}).
     * </p>
     */
    boolean isReversed(UUID tenantId, UUID adjustmentId);

    /**
     * Serialises concurrent writers of one usage event's ledger for the duration of
     * the current transaction.
     *
     * <p>
     * The "net must not go negative" rule is read-then-write, so without this two
     * submissions can each observe a healthy net and jointly overshoot. Callers
     * must already be inside a transaction.
     * </p>
     */
    void lockUsageEvent(UUID usageEventId);
}
