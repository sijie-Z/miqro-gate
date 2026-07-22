package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link UpstreamSubscription} entities.
 */
public interface UpstreamSubscriptionRepository {

    Optional<UpstreamSubscription> findById(UUID id);

    List<UpstreamSubscription> findAllByProviderProductId(UUID providerProductId);

    UpstreamSubscription insert(UpstreamSubscription subscription);

    UpstreamSubscription update(UpstreamSubscription subscription);
}
