package com.miqroera.miqrokey.domain.usage;

import java.util.UUID;

/**
 * The validation view of one usage event about to be adjusted (#709): its
 * observed token counts from {@code usage_event}, together with the deltas
 * already recorded against it in {@code usage_adjustments}.
 *
 * <p>
 * The two halves are read together on purpose. Whether a proposed correction is
 * acceptable depends on the <em>running</em> net, not on the observed count
 * alone — a second correction that would drive a dimension below zero must be
 * refused.
 * </p>
 *
 * <p>
 * Observed counts are nullable (coalesced and cache-hit rows carry none).
 * Deltas are never null, defaulting to zero. Netting treats a null observed
 * count as zero <em>only when a delta exists</em> — that is the legitimate "the
 * gateway recorded nothing but the provider billed something" correction — and
 * otherwise stays null so "no data" is not silently confused with "zero
 * tokens".
 * </p>
 */
public record AdjustmentTarget(UUID usageEventId, UUID tenantId, CacheLevel cacheLevel, Long inputTokens,
        Long outputTokens, Long cacheReadInputTokens, Long cacheCreationInputTokens, long inputDelta, long outputDelta,
        long cacheReadDelta, long cacheCreationDelta) {

    public Long netInputTokens() {
        return net(inputTokens, inputDelta);
    }

    public Long netOutputTokens() {
        return net(outputTokens, outputDelta);
    }

    public Long netCacheReadTokens() {
        return net(cacheReadInputTokens, cacheReadDelta);
    }

    public Long netCacheCreationTokens() {
        return net(cacheCreationInputTokens, cacheCreationDelta);
    }

    /** True when the event carries at least one observed token count. */
    public boolean hasObservedUsage() {
        return inputTokens != null || outputTokens != null || cacheReadInputTokens != null
                || cacheCreationInputTokens != null;
    }

    /**
     * True when applying the deltas recorded so far would leave some dimension
     * below zero — i.e. the ledger already over-corrected.
     */
    public boolean anyNetNegative() {
        return isNegative(netInputTokens()) || isNegative(netOutputTokens()) || isNegative(netCacheReadTokens())
                || isNegative(netCacheCreationTokens());
    }

    private static boolean isNegative(Long value) {
        return value != null && value < 0L;
    }

    private static Long net(Long observed, long delta) {
        if (observed == null && delta == 0L) {
            return null;
        }
        return (observed == null ? 0L : observed) + delta;
    }
}
