package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ApiConsumerView;
import com.miqroera.miqrokey.controlplane.security.ConsumerJwtVerifier;
import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
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
 * public keys stored in PEM, never a secret.
 */
@Service
public class ApiConsumerService {

    private final ApiConsumerRepository repository;
    private final ConsumerJwtVerifier jwtVerifier;

    public ApiConsumerService(ApiConsumerRepository repository, ConsumerJwtVerifier jwtVerifier) {
        this.repository = repository;
        this.jwtVerifier = jwtVerifier;
    }

    public List<ApiConsumerView> list(UUID tenantId) {
        return repository.findAllByTenantId(tenantId).stream().map(this::toView).toList();
    }

    @Transactional
    public CreatedConsumer create(UUID tenantId, String name) {
        ApiConsumer.GeneratedKey key = ApiConsumer.generateKey();
        ApiConsumer consumer = new ApiConsumer(UUID.randomUUID(), tenantId, name, key.digest(), key.prefix(), "ACTIVE",
                null, null, null, 0, Instant.now(), Instant.now());
        try {
            repository.insert(consumer);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_NAME_TAKEN", "消费者名称已存在。");
        }
        return new CreatedConsumer(toView(consumer), key.plaintext());
    }

    @Transactional
    public ApiConsumerView disable(UUID tenantId, UUID consumerId) {
        ApiConsumer consumer = find(tenantId, consumerId);
        if ("DISABLED".equals(consumer.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_ALREADY_DISABLED", "消费者已禁用。");
        }
        return toView(repository.update(withVersion(consumer, "DISABLED", null, null, null)));
    }

    /**
     * Sets or rotates the consumer's RS256 JWT verification key. The PEM must parse
     * to an RSA public key; the fingerprint (SHA-256, first 8 bytes hex) is
     * returned via the view. Rotation is immediate: old tokens signed with a
     * previous key stop verifying.
     */
    @Transactional
    public ApiConsumerView setJwtKey(UUID tenantId, UUID consumerId, String publicKeyPem) {
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
        return toView(repository.update(withVersion(consumer, consumer.status(), pem, fingerprint, now)));
    }

    /**
     * Removes the JWT verification key; JWT auth for this consumer stops
     * immediately.
     */
    @Transactional
    public ApiConsumerView removeJwtKey(UUID tenantId, UUID consumerId) {
        ApiConsumer consumer = find(tenantId, consumerId);
        return toView(repository.update(withVersion(consumer, consumer.status(), null, null, null)));
    }

    private ApiConsumer find(UUID tenantId, UUID consumerId) {
        return repository.findByIdAndTenantId(consumerId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONSUMER_NOT_FOUND", "消费者不存在。"));
    }

    private static ApiConsumer withVersion(ApiConsumer consumer, String status, String pem, String fingerprint,
            Instant jwtKeySetAt) {
        return new ApiConsumer(consumer.id(), consumer.tenantId(), consumer.name(), consumer.keyDigest(),
                consumer.keyPrefix(), status, pem, fingerprint, jwtKeySetAt, consumer.version(), consumer.createdAt(),
                consumer.updatedAt());
    }

    private ApiConsumerView toView(ApiConsumer consumer) {
        return new ApiConsumerView(consumer.id(), consumer.name(), consumer.keyPrefix(), consumer.status(),
                consumer.jwtKeyFingerprint(), consumer.jwtKeySetAt(), consumer.createdAt());
    }

    public record CreatedConsumer(ApiConsumerView consumer, String apiKey) {
    }
}
