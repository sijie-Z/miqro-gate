package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.ConfigEntry;
import com.miqroera.miqrokey.domain.repository.ConfigEntryRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
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
    private final AuditService auditService;

    public AdminConfigService(ConfigEntryRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public List<ConfigEntry> list(UUID tenantId, String groupName) {
        return groupName == null || groupName.isBlank()
                ? repository.findAllByTenantId(tenantId)
                : repository.findAllByGroup(tenantId, groupName);
    }

    /** Creates or updates the (group, key) entry in place. */
    @Transactional
    public ConfigEntry put(UUID tenantId, UUID adminId, String groupName, String key, String value, String description,
            AuditContext context) {
        String normalizedGroup = groupName.trim();
        String normalizedKey = key.trim();
        validateName(normalizedGroup, "配置分组");
        validateName(normalizedKey, "配置键");
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIG_VALUE_REQUIRED", "配置值必填。");
        }
        ConfigEntry entry = new ConfigEntry(UUID.randomUUID(), tenantId, normalizedGroup, normalizedKey, value,
                description, adminId, 0, Instant.now(), Instant.now());
        ConfigEntry stored = repository.upsert(entry);
        // Summaries carry group/key only — config values may contain sensitive data.
        auditService.record(tenantId, context.actorId(), "CONFIG_PUT", "CONFIG", stored.id(),
                AuditSummaries.summary(context, "group", normalizedGroup, "key", normalizedKey), context.requestId());
        return stored;
    }

    @Transactional
    public void delete(UUID tenantId, String groupName, String key, AuditContext context) {
        if (!repository.delete(tenantId, groupName, key)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", "配置项不存在。");
        }
        auditService.record(tenantId, context.actorId(), "CONFIG_DELETE", "CONFIG", null, AuditSummaries
                .summary(context, "group", AuditSummaries.sanitize(groupName), "key", AuditSummaries.sanitize(key)),
                context.requestId());
    }

    private static void validateName(String name, String label) {
        if (!name.matches("[a-zA-Z][a-zA-Z0-9._-]{0,127}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIG_NAME_INVALID",
                    label + "必须为字母开头、仅含字母数字与 ._-，且不超过 128 字符。");
        }
    }
}
