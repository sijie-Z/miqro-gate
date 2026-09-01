package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.ConfigEntry;
import com.miqroera.miqrokey.domain.repository.ConfigEntryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Global configuration center (P3.3, {@code config_entries} V19): grouped
 * key-value entries managed by admins with optimistic upsert. Non-secret
 * configuration only — secrets stay in the env / encrypted-secret pipeline.
 */
@Service
public class AdminConfigService {

    private final ConfigEntryRepository repository;

    public AdminConfigService(ConfigEntryRepository repository) {
        this.repository = repository;
    }

    public List<ConfigEntry> list(UUID tenantId, String groupName) {
        return groupName == null || groupName.isBlank()
                ? repository.findAllByTenantId(tenantId)
                : repository.findAllByGroup(tenantId, groupName);
    }

    /** Creates or updates the (group, key) entry in place. */
    @Transactional
    public ConfigEntry put(UUID tenantId, UUID adminId, String groupName, String key, String value,
            String description) {
        String normalizedGroup = groupName.trim();
        String normalizedKey = key.trim();
        validateName(normalizedGroup, "配置分组");
        validateName(normalizedKey, "配置键");
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIG_VALUE_REQUIRED", "配置值必填。");
        }
        ConfigEntry entry = new ConfigEntry(UUID.randomUUID(), tenantId, normalizedGroup, normalizedKey, value,
                description, adminId, 0, Instant.now(), Instant.now());
        return repository.upsert(entry);
    }

    @Transactional
    public void delete(UUID tenantId, String groupName, String key) {
        if (!repository.delete(tenantId, groupName, key)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", "配置项不存在。");
        }
    }

    private static void validateName(String name, String label) {
        if (!name.matches("[a-zA-Z][a-zA-Z0-9._-]{0,127}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIG_NAME_INVALID",
                    label + "必须为字母开头、仅含字母数字与 ._-，且不超过 128 字符。");
        }
    }
}
