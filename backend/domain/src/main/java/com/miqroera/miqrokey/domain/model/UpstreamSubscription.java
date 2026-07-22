package com.miqroera.miqrokey.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An actual purchased or activated subscription/account resource. PAYG APIs may
 * use a period-less subscription as an account container.
 */
public record UpstreamSubscription(UUID id, UUID tenantId, UUID providerProductId, String name,
        String externalAccountRef, BillingMode billingMode, PlanScope planScope, BigDecimal subscriptionPrice,
        String currency, Instant periodStart, Instant periodEnd, Instant renewalAt, Long quotaTotal, String quotaUnit,
        SubscriptionStatus status, Instant lastStatusSyncAt, StatusSource statusSource, long version, Instant createdAt,
        Instant updatedAt) {
}
