package com.miqroera.miqrokey.domain.repository;

import com.miqroera.miqrokey.domain.model.ConfigEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code config_entries} (V19, P3.3 global configuration center).
 */
public interface ConfigEntryRepository {

    /** Insert or update the (group, key) entry; returns the stored row. */
    ConfigEntry upsert(ConfigEntry entry);

    Optional<ConfigEntry> findByGroupAndKey(UUID tenantId, String groupName, String key);

    List<ConfigEntry> findAllByTenantId(UUID tenantId);

    List<ConfigEntry> findAllByGroup(UUID tenantId, String groupName);

    /** True when a row was removed. */
    boolean delete(UUID tenantId, String groupName, String key);
}
