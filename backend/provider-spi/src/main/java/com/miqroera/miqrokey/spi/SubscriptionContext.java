package com.miqroera.miqrokey.spi;

import java.util.UUID;

/**
 * What the control plane knows about the subscription whose plan status an
 * adapter is asked to fetch.
 *
 * @param subscriptionId
 *            internal subscription id
 * @param kind
 *            plan kind
 * @param providerSubscriptionId
 *            provider-side subscription/member id, when the product defines one
 *            (may be {@code null})
 */
public record SubscriptionContext(UUID subscriptionId, SubscriptionKind kind, String providerSubscriptionId) {

    public SubscriptionContext {
        if (subscriptionId == null || kind == null) {
            throw new IllegalArgumentException("subscriptionId/kind must not be null");
        }
    }
}
