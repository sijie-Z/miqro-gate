package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.UpstreamCredentialVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link UpstreamCredentialVersion} immutable entities.
 */
public interface UpstreamCredentialVersionRepository {

    Optional<UpstreamCredentialVersion> findById(UUID id);

    List<UpstreamCredentialVersion> findAllByCredentialId(UUID credentialId);

    Optional<UpstreamCredentialVersion> findActiveByCredentialId(UUID credentialId);

    UpstreamCredentialVersion insert(UpstreamCredentialVersion version);

    UpstreamCredentialVersion update(UpstreamCredentialVersion version);
}
