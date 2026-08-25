package com.miqroera.miqrokey.spi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable snapshot of a subscription's plan status. Amounts use
 * {@code decimal} quantities in the provider's natural unit (tokens, credits,
 * CNY, ...); the meaning of each field is defined by the adapter for the
 * product.
 *
 * @param subscriptionId
 *            upstream subscription the snapshot belongs to
 * @param kind
 *            plan kind (PAYG/INDIVIDUAL/TEAM/ENTERPRISE)
 * @param total
 *            total entitlement; {@code null} when unknown
 * @param used
 *            consumed amount; {@code null} when unknown
 * @param remaining
 *            remaining amount; {@code null} when unknown
 * @param periodStart
 *            billing period start (may be {@code null})
 * @param periodEnd
 *            billing period end (may be {@code null})
 * @param seats
 *            team seats (TEAM_PLAN); {@code null} when not applicable
 * @param members
 *            team member count (TEAM_PLAN); {@code null} when not applicable
 * @param sharedPool
 *            whether the plan is a shared pool (multiple keys share quota)
 * @param source
 *            where the values come from
 * @param fetchedAt
 *            when the values were fetched
 */
public record PlanSnapshot(String subscriptionId, SubscriptionKind kind, BigDecimal total, BigDecimal used,
        BigDecimal remaining, Instant periodStart, Instant periodEnd, Integer seats, Integer members,
        boolean sharedPool, PlanDataSource source, Instant fetchedAt) {

    public PlanSnapshot {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new IllegalArgumentException("subscriptionId must not be blank");
        }
        if (kind == null || source == null || fetchedAt == null) {
            throw new IllegalArgumentException("kind/source/fetchedAt must not be null");
        }
    }
}
