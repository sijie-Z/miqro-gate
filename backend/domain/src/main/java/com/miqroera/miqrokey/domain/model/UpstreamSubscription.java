package com.miqroera.miqrokey.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An actual purchased or activated subscription/account resource. PAYG APIs may
 * use a period-less subscription as an account container.
 */
public record UpstreamSubscription(UUID id, UUID providerProductId, String name, String externalAccountRef,
        String billingMode, String planScope, BigDecimal subscriptionPrice, String currency, Instant periodStart,
        Instant periodEnd, Instant renewalAt, Long quotaTotal, String quotaUnit, String status,
        Instant lastStatusSyncAt, String statusSource, long version, Instant createdAt, Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
}
