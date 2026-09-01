package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.ApiConsumerView;
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
 * Admin lifecycle for external-system API consumers (ADR-0010): create
 * (one-time plaintext key), list, disable. Keys are stored as SHA-256 digests
 * only; the plaintext is never recoverable after creation.
 */
@Service
public class ApiConsumerService {

    private final ApiConsumerRepository repository;

    public ApiConsumerService(ApiConsumerRepository repository) {
        this.repository = repository;
    }

    public List<ApiConsumerView> list(UUID tenantId) {
        return repository.findAllByTenantId(tenantId).stream().map(this::toView).toList();
    }

    @Transactional
    public CreatedConsumer create(UUID tenantId, String name) {
        ApiConsumer.GeneratedKey key = ApiConsumer.generateKey();
        ApiConsumer consumer = new ApiConsumer(UUID.randomUUID(), tenantId, name, key.digest(), key.prefix(), "ACTIVE",
                0, Instant.now(), Instant.now());
        try {
            repository.insert(consumer);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_NAME_TAKEN", "消费者名称已存在。");
        }
        return new CreatedConsumer(toView(consumer), key.plaintext());
    }

    @Transactional
    public ApiConsumerView disable(UUID tenantId, UUID consumerId) {
        ApiConsumer consumer = repository.findByIdAndTenantId(consumerId, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONSUMER_NOT_FOUND", "消费者不存在。"));
        if ("DISABLED".equals(consumer.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSUMER_ALREADY_DISABLED", "消费者已禁用。");
        }
        ApiConsumer updated = repository.update(new ApiConsumer(consumer.id(), consumer.tenantId(), consumer.name(),
                consumer.keyDigest(), consumer.keyPrefix(), "DISABLED", consumer.version(), consumer.createdAt(),
                consumer.updatedAt()));
        return toView(updated);
    }

    private ApiConsumerView toView(ApiConsumer consumer) {
        return new ApiConsumerView(consumer.id(), consumer.name(), consumer.keyPrefix(), consumer.status(),
                consumer.createdAt());
    }

    public record CreatedConsumer(ApiConsumerView consumer, String apiKey) {
    }
}
