package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.dto.PriceSnapshotView;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin unit-price catalog (api-contract §5.9): per (product, model, token
 * type) price per one million tokens. Prices are immutable snapshots — edits
 * append a new snapshot so historical cost computation is never recomputed
 * (mirrors the upstream console semantics: "修改不追溯").
 */
@Service
public class AdminPriceService {

    private final PriceSnapshotRepository priceRepository;
    private final ProviderProductRepository productRepository;
    private final AuditService auditService;

    public AdminPriceService(PriceSnapshotRepository priceRepository, ProviderProductRepository productRepository,
            AuditService auditService) {
        this.priceRepository = priceRepository;
        this.productRepository = productRepository;
        this.auditService = auditService;
    }

    public List<PriceSnapshotView> listLatest() {
        return priceRepository.findAllLatestAt(Instant.now()).stream().map(AdminPriceService::toView).toList();
    }

    @Transactional
    public PriceSnapshotView create(UUID tenantId, UUID productId, String modelId, String tokenType, String currency,
            BigDecimal unitPrice, String source, UUID createdBy) {
        if (productRepository.findById(productId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "供应商产品不存在。");
        }
        final PriceTokenType type;
        try {
            type = PriceTokenType.valueOf(tokenType);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "tokenType 非法。");
        }
        var snapshot = priceRepository.insert(new PriceSnapshot(UUID.randomUUID(), productId, modelId, type, currency,
                unitPrice, Instant.now(), source, createdBy, Instant.now()));
        auditService.record(tenantId, createdBy, "PRICE_CREATE", "PRICE_SNAPSHOT", snapshot.id(),
                "{\"product\":\"" + productId + "\",\"model\":\"" + modelId + "\",\"tokenType\":\"" + type + "\"}",
                null);
        return toView(snapshot);
    }

    private static PriceSnapshotView toView(PriceSnapshot s) {
        return new PriceSnapshotView(s.id(), s.providerProductId(), s.modelId(), s.tokenType().name(), s.currency(),
                s.unitPrice(), s.effectiveFrom(), s.source(), s.createdBy(), s.createdAt());
    }
}
