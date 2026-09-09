package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.AdminApiKeyView;
import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.model.AdminApiKeyCapabilities;
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
                generated.prefix(), actorId, expiresAt, null, Instant.now(), null);
        try {
            repository.insert(key);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_API_KEY_NAME_TAKEN", "同名管理密钥已存在。");
        }
        auditService.record(tenantId, actorId, "ADMIN_API_KEY_ISSUE", "ADMIN_API_KEY", key.id(),
                "{\"name\":\"" + safeJson(name) + "\"}", null);
        return new Issued(key.id(), generated.plaintext());
    }

    /**
     * F60 batch 3: narrows (or widens) a key's capability scope. A {@code null} or
     * empty list resets to full access / deny-all respectively; validation rejects
     * unknown or duplicate codes. Changes are audited with the previous and next
     * scope so the trail shows who shrank which key.
     */
    @Transactional
    public AdminApiKeyView updateScope(UUID tenantId, UUID actorId, UUID keyId, List<String> capabilities) {
        AdminApiKey key = repository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ADMIN_API_KEY_NOT_FOUND", "管理密钥不存在。"));
        if (!AdminApiKeyCapabilities.isValid(capabilities)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_API_KEY_SCOPE_INVALID",
                    "能力组只能取 usage:read/alerts:write/exports:create/vkeys:delegate,且不得重复。");
        }
        repository.updateScope(keyId, tenantId, capabilities);
        auditService.record(tenantId, actorId, "ADMIN_API_KEY_SCOPE_UPDATE", "ADMIN_API_KEY", keyId,
                "{\"name\":\"" + safeJson(key.name()) + "\",\"from\":" + scopeJson(key.capabilities()) + ",\"to\":"
                        + scopeJson(capabilities) + "}",
                null);
        return AdminApiKeyView.from(repository.findByIdAndTenantId(keyId, tenantId).orElseThrow());
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

    /** Compact JSON array for the audit summary (codes are fixed ASCII). */
    private static String scopeJson(List<String> capabilities) {
        if (capabilities == null) {
            return "null";
        }
        return "[\"" + String.join("\",\"", capabilities) + "\"]";
    }
}
