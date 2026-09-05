package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.RetentionConfig;
import com.miqroera.miqrokey.domain.repository.RetentionConfigRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Compliance-retention switch (ADR-0014 v3 Accepted, V31, api-contract §5.26):
 * per-tenant enable/disable of the (default-off) user-message retention
 * channel. Enabling collects nothing by itself — the gateway side-channel is
 * built and gated separately — but every change is audited and immediately
 * published to the route snapshot so a running gateway picks it up without
 * restart.
 */
@Service
public class AdminRetentionConfigService {

    private final RetentionConfigRepository repository;
    private final AuditService auditService;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public AdminRetentionConfigService(RetentionConfigRepository repository, AuditService auditService,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.repository = repository;
        this.auditService = auditService;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    public RetentionConfig view(UUID tenantId) {
        return repository.find(tenantId).orElse(RetentionConfig.disabled());
    }

    @Transactional
    public RetentionConfig configure(UUID tenantId, UUID adminId, Boolean enabled, String requestId) {
        boolean enable = Boolean.TRUE.equals(enabled);
        RetentionConfig current = view(tenantId);
        RetentionConfig next = new RetentionConfig(enable, current.contentScope(), current.keyVersion(),
                current.version());
        RetentionConfig stored = repository.upsert(tenantId, next, adminId);
        auditService.record(tenantId, adminId, "RETENTION_CONFIG_UPDATE", "TENANT", tenantId,
                "{\"enabled\":" + stored.enabled() + ",\"contentScope\":\"" + stored.contentScope()
                        + "\",\"keyVersion\":\"" + stored.keyVersion() + "\",\"version\":" + stored.version() + "}",
                requestId);
        routeRefreshPublisher.publishChanged();
        return stored;
    }
}
