package com.miqroera.miqrokey.spi;

/**
 * The billing/entitlement shape of a provider subscription. Team plans must
 * never be collapsed into a single shared API key; the concrete topology is
 * modelled by the adapter ({@code docs/provider-catalog.md §5}).
 */
public enum SubscriptionKind {

    /** Pay-as-you-go per-token billing. */
    PAYG,

    /** Individual plan (e.g. a personal Coding Plan or Token Plan). */
    INDIVIDUAL_PLAN,

    /** Team plan (seats, member keys, shared pools — see provider-catalog.md). */
    TEAM_PLAN,

    /** Enterprise plan (tenant-wide pools, quotas, dedicated support). */
    ENTERPRISE_PLAN,
}
