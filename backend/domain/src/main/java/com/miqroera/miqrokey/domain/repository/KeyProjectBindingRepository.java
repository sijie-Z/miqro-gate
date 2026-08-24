package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.KeyProjectBinding;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link KeyProjectBinding} — the authorization authority for
 * label routing (ADR-0008). A virtual key is bound to exactly one project; the
 * binding decides which project a presented key may address.
 */
public interface KeyProjectBindingRepository {

    Optional<KeyProjectBinding> findById(UUID id);

    /**
     * The single binding of a key (the schema permits one row per (virtual_key_id,
     * project_id); callers should treat this as the current binding).
     */
    Optional<KeyProjectBinding> findByVirtualKeyId(UUID virtualKeyId);

    KeyProjectBinding insert(KeyProjectBinding binding);

    KeyProjectBinding update(KeyProjectBinding binding);
}
