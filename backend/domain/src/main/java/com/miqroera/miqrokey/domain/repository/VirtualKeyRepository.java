package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.VirtualKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    List<VirtualKey> findAllByTenantId(UUID tenantId);

    VirtualKey insert(VirtualKey virtualKey);

    VirtualKey update(VirtualKey virtualKey);

    boolean existsByPublicKeyId(String publicKeyId);

    /**
     * Inserts the model authorization snapshot rows for a key
     * ({@code virtual_key_models}). The set is replaced atomically: existing rows
     * for the key are deleted first, then the given models are inserted. The
     * gateway snapshot picks the rows up within one refresh interval.
     */
    void replaceKeyModels(UUID tenantId, UUID virtualKeyId, Collection<String> modelIds);

    /**
     * Returns the model IDs authorized for a key (the intersection of the
     * creation-time snapshot and the grant is enforced by the gateway snapshot).
     */
    Set<String> findModelIds(UUID virtualKeyId);
}
