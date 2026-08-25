package com.miqroera.miqrokey.spi;

/**
 * Declared capabilities of an adapter. The Control Plane uses these flags to
 * decide which admin UI panels and background jobs apply to a product; it must
 * never branch on {@code vendor} strings.
 *
 * @param streaming
 *            byte-transparent streaming supported (SSE etc.)
 * @param modelDiscovery
 *            model catalog fetch supported
 * @param balance
 *            official balance/entitlement query supported
 * @param plan
 *            plan status query supported
 * @param teamPlan
 *            team-plan modelling supported (seats/member keys/pools)
 * @param requestId
 *            provider request id is captured into usage
 * @param usageLocation
 *            where usage values come from
 */
public record AdapterCapabilities(boolean streaming, boolean modelDiscovery, boolean balance, boolean plan,
        boolean teamPlan, boolean requestId, UsageSource usageLocation) {

    public AdapterCapabilities {
        if (usageLocation == null) {
            throw new IllegalArgumentException("usageLocation must not be null");
        }
    }
}
