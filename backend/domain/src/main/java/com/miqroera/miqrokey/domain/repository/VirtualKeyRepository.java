package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.VirtualKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link VirtualKey} entities.
 */
public interface VirtualKeyRepository {

    Optional<VirtualKey> findById(UUID id);

    Optional<VirtualKey> findByPublicKeyId(String publicKeyId);

    List<VirtualKey> findAllByUserId(UUID userId);

    List<VirtualKey> findAllByProjectId(UUID projectId);

    List<VirtualKey> findAllByGrantId(UUID grantId);

    VirtualKey insert(VirtualKey virtualKey);

    VirtualKey update(VirtualKey virtualKey);

    boolean existsByPublicKeyId(String publicKeyId);
}
