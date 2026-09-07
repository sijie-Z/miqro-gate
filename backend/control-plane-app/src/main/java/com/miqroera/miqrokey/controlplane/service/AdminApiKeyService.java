package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AdminApiKeyView;
import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle for open admin API keys (ADR-0015): issue (one-time plaintext),
 * list and revoke. Machine keys authenticate the programmable admin surface
 * ({@code /api/v1/admin-api/**}); revocation is immediate because the auth
 * filter resolves the digest per request.
 */
@Service
public class AdminApiKeyService {

    private final AdminApiKeyRepository repository;
    private final AuditService auditService;

    public AdminApiKeyService(AdminApiKeyRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public record Issued(UUID id, String secret) {
    }

    public List<AdminApiKeyView> list(UUID tenantId) {
        return repository.findAllByTenantId(tenantId).stream().map(AdminApiKeyView::from).toList();
    }

    @Transactional
    public Issued issue(UUID tenantId, UUID actorId, String name, Instant expiresAt) {
        AdminApiKey.GeneratedKey generated = AdminApiKey.generateKey();
        AdminApiKey key = new AdminApiKey(UUID.randomUUID(), tenantId, name.trim(), generated.digest(),
                generated.prefix(), actorId, expiresAt, null, Instant.now());
        try {
            repository.insert(key);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_API_KEY_NAME_TAKEN", "同名管理密钥已存在。");
        }
        auditService.record(tenantId, actorId, "ADMIN_API_KEY_ISSUE", "ADMIN_API_KEY", key.id(),
                "{\"name\":\"" + safeJson(name) + "\"}", null);
        return new Issued(key.id(), generated.plaintext());
    }

    @Transactional
    public AdminApiKeyView revoke(UUID tenantId, UUID actorId, UUID keyId) {
        AdminApiKey key = repository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ADMIN_API_KEY_NOT_FOUND", "管理密钥不存在。"));
        if (key.revokedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_API_KEY_ALREADY_REVOKED", "管理密钥已吊销。");
        }
        repository.revoke(keyId, tenantId, Instant.now());
        auditService.record(tenantId, actorId, "ADMIN_API_KEY_REVOKE", "ADMIN_API_KEY", keyId,
                "{\"name\":\"" + safeJson(key.name()) + "\"}", null);
        return AdminApiKeyView.from(repository.findByIdAndTenantId(keyId, tenantId).orElseThrow());
    }

    private static String safeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
