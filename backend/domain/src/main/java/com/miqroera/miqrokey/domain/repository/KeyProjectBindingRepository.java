package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.KeyProjectBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link KeyProjectBinding} — the authorization authority for
 * label routing (ADR-0018). A virtual key may be bound to several projects (one
 * row per project); the binding matched by the presented label decides which
 * project, credential and model scope the request resolves to.
 */
public interface KeyProjectBindingRepository {

    Optional<KeyProjectBinding> findById(UUID id);

    /** Every binding row of a key, oldest first (stable display order). */
    List<KeyProjectBinding> findAllByVirtualKeyId(UUID virtualKeyId);

    KeyProjectBinding insert(KeyProjectBinding binding);

    KeyProjectBinding update(KeyProjectBinding binding);
}
