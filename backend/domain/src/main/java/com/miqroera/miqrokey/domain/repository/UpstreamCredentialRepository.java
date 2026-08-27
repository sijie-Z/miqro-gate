package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link UpstreamCredential} entities.
 */
public interface UpstreamCredentialRepository {

    Optional<UpstreamCredential> findById(UUID id);

    /**
     * Reads the credential row under a write lock ({@code FOR UPDATE}) so callers
     * can serialize concurrent lifecycle transitions (rotate/disable) against the
     * same credential.
     */
    Optional<UpstreamCredential> findByIdForUpdate(UUID id);

    List<UpstreamCredential> findAllBySubscriptionId(UUID subscriptionId);

    List<UpstreamCredential> findAllByTenantId(UUID tenantId);

    List<UpstreamCredential> findAllBySubscriptionIdAndStatus(UUID subscriptionId, String status);

    UpstreamCredential insert(UpstreamCredential credential);

    UpstreamCredential update(UpstreamCredential credential);

    boolean existsById(UUID id);
}
