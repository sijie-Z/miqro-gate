package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ApiConsumerView;
import com.miqroera.miqrokey.controlplane.security.ConsumerJwtVerifier;
import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.model.ConsumerCapabilities;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin lifecycle for external-system API consumers (ADR-0010/0011): create
 * (one-time plaintext key), list, disable, and manage the optional RS256 JWT
 * verification key. API keys are stored as SHA-256 digests only; JWT keys are
 * public keys stored in PEM, never a secret. Every mutation records an audit
 * event (CONSUMER_CREATE/DISABLE/JWT_KEY_SET/JWT_KEY_REMOVED); summaries carry
 * names and fingerprints only — never the plaintext key.
 */
@Service
public class ApiConsumerService {

    private final ApiConsumerRepository repository;
    private final ConsumerJwtVerifier jwtVerifier;
    private final RouteRefreshPublisher routeRefreshPublisher;
    private final AuditService auditService;

    public ApiConsumerService(ApiConsumerRepository repository, ConsumerJwtVerifier jwtVerifier,
            RouteRefreshPublisher routeRefreshPublisher, AuditService auditService) {
        this.repository = repository;
        this.jwtVerifier = jwtVerifier;
        this.routeRefreshPublisher = routeRefreshPublisher;
        this.auditService = auditService;
    }

    public List<ApiConsumerView> list(UUID tenantId) {
        return repository.findAllByTenantId(tenantId).stream().map(this::toView).toList();
    }

    @Transactional
    public CreatedConsumer create(UUID tenantId, UUID adminId, String name, String requestId) {
        ApiConsumer.GeneratedKey key = ApiConsumer.generateKey();
        ApiConsumer consumer = new ApiConsumer(UUID.randomUUID(), tenantId, name.trim(), key.digest(), key.prefix(),
                "ACTIVE", null, null, null, 0, Instant.now(), Instant.now());
        try {
            repository.insert(consumer);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_NAME_TAKEN", "消费者名称已存在。");
        }
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "CONSUMER_CREATE", "CONSUMER", consumer.id(),
                AuditSummaries.summary("name", AuditSummaries.sanitize(consumer.name())), requestId);
        return new CreatedConsumer(toView(consumer), key.plaintext());
    }

    @Transactional
    public ApiConsumerView disable(UUID tenantId, UUID adminId, UUID consumerId, String requestId) {
        ApiConsumer consumer = find(tenantId, consumerId);
        if ("DISABLED".equals(consumer.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_ALREADY_DISABLED", "消费者已禁用。");
        }
        ApiConsumerView view = toView(repository.update(withVersion(consumer, "DISABLED", null, null, null)));
        routeRefreshPublisher.publishChanged();
        auditService.record(tenantId, adminId, "CONSUMER_DISABLE", "CONSUMER", consumerId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(consumer.name())), requestId);
        return view;
    }

    /**
     * Sets or rotates the consumer's RS256 JWT verification key. The PEM must parse
     * to an RSA public key; the fingerprint (SHA-256, first 8 bytes hex) is
     * returned via the view. Rotation is immediate: old tokens signed with a
     * previous key stop verifying.
     */
    @Transactional
    public ApiConsumerView setJwtKey(UUID tenantId, UUID adminId, UUID consumerId, String publicKeyPem,
            String requestId) {
        ApiConsumer consumer = find(tenantId, consumerId);
        if ("DISABLED".equals(consumer.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_DISABLED", "消费者已禁用。");
        }
        String pem = publicKeyPem.trim();
        java.security.PublicKey key;
        try {
            key = ConsumerJwtVerifier.parsePublicKey(pem);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "JWT_KEY_INVALID",
                    "公钥必须是有效的 RSA PEM（SubjectPublicKeyInfo）。");
        }
        Instant now = Instant.now();
        String fingerprint = ConsumerJwtVerifier.fingerprint(key);
        ApiConsumerView view = toView(
                repository.update(withVersion(consumer, consumer.status(), pem, fingerprint, now)));
        auditService.record(tenantId, adminId, "CONSUMER_JWT_KEY_SET", "CONSUMER", consumerId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(consumer.name()), "fingerprint", fingerprint),
                requestId);
        return view;
    }

    /**
     * Removes the JWT verification key; JWT auth for this consumer stops
     * immediately.
     */
    @Transactional
    public ApiConsumerView removeJwtKey(UUID tenantId, UUID adminId, UUID consumerId, String requestId) {
        ApiConsumer consumer = find(tenantId, consumerId);
        ApiConsumerView view = toView(repository.update(withVersion(consumer, consumer.status(), null, null, null)));
        auditService.record(tenantId, adminId, "CONSUMER_JWT_KEY_REMOVED", "CONSUMER", consumerId,
                AuditSummaries.summary("name", AuditSummaries.sanitize(consumer.name())), requestId);
        return view;
    }

    private ApiConsumer find(UUID tenantId, UUID consumerId) {
        return repository.findByIdAndTenantId(consumerId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONSUMER_NOT_FOUND", "消费者不存在。"));
    }

    private static ApiConsumer withVersion(ApiConsumer consumer, String status, String pem, String fingerprint,
            Instant jwtKeySetAt) {
        return new ApiConsumer(consumer.id(), consumer.tenantId(), consumer.name(), consumer.keyDigest(),
                consumer.keyPrefix(), status, pem, fingerprint, jwtKeySetAt, consumer.version(), consumer.createdAt(),
                consumer.updatedAt(), consumer.capabilities());
    }

    private ApiConsumerView toView(ApiConsumer consumer) {
        return new ApiConsumerView(consumer.id(), consumer.name(), consumer.keyPrefix(), consumer.status(),
                consumer.jwtKeyFingerprint(), consumer.jwtKeySetAt(), consumer.createdAt(),
                consumer.capabilities() == null ? null : List.copyOf(consumer.capabilities()));
    }

    /**
     * Issue #316: replaces the channel scope (null = full access, empty = no
     * channels). Validation rejects unknown or duplicate codes; changes are audited
     * with the previous and next scope so the trail shows who shrank which
     * consumer.
     */
    @Transactional
    public ApiConsumerView updateScope(UUID tenantId, UUID adminId, UUID consumerId, List<String> capabilities,
            String requestId) {
        ApiConsumer consumer = find(tenantId, consumerId);
        if (!ConsumerCapabilities.isValid(capabilities)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONSUMER_SCOPE_INVALID",
                    "能力只能取 billing:read/mcp:call，且不得重复。");
        }
        repository.updateCapabilities(consumerId, tenantId, capabilities);
        routeRefreshPublisher.publishChanged();
        auditService
                .record(tenantId, adminId, "CONSUMER_SCOPE_UPDATE", "CONSUMER", consumerId,
                        "{\"name\":\"" + AuditSummaries.sanitize(consumer.name()) + "\",\"from\":"
                                + scopeJson(consumer.capabilities()) + ",\"to\":" + scopeJson(capabilities) + "}",
                        requestId);
        return toView(repository.findByIdAndTenantId(consumerId, tenantId).orElseThrow());
    }

    private static String scopeJson(List<String> capabilities) {
        if (capabilities == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < capabilities.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(capabilities.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    public record CreatedConsumer(ApiConsumerView consumer, String apiKey) {
    }
}
