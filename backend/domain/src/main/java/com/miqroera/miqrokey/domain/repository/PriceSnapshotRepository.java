package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PriceSnapshot} — manual/official unit prices per
 * million tokens for the global provider product catalog (not tenant-scoped).
 */
public interface PriceSnapshotRepository {

    PriceSnapshot insert(PriceSnapshot snapshot);

    /**
     * The latest price effective at or before {@code at} for a (product, model,
     * token type) triple, if one exists.
     */
    Optional<PriceSnapshot> findLatestAt(UUID providerProductId, String modelId, PriceTokenType tokenType, Instant at);

    /**
     * Every product's latest price effective at or before {@code at}, one row per
     * (product, model, token type). The catalog is small (single tenant, dozens of
     * products), so a full sweep is cheap and lets the usage aggregator build its
     * price map in one call.
     */
    List<PriceSnapshot> findAllLatestAt(Instant at);
}
