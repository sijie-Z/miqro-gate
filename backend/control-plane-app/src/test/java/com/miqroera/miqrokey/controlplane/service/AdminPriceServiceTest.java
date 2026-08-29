package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.usage.PriceSnapshot;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPriceServiceTest {

    @Mock
    private PriceSnapshotRepository priceRepository;
    @Mock
    private ProviderProductRepository productRepository;
    @Mock
    private AuditService auditService;

    private AdminPriceService service;

    private static final UUID PRODUCT_ID = UUID.fromString("0190-0000-0000-0000-000000000020");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("0190-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new AdminPriceService(priceRepository, productRepository, auditService);
    }

    @Test
    void createsSnapshotForExistingProduct() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(priceRepository.insert(any(PriceSnapshot.class))).thenAnswer(inv -> {
            var s = inv.getArgument(0, PriceSnapshot.class);
            return new PriceSnapshot(UUID.fromString("0190-0000-0000-0000-000000000030"), s.providerProductId(),
                    s.modelId(), s.tokenType(), s.currency(), s.unitPrice(), s.effectiveFrom(), s.source(),
                    s.createdBy(), s.createdAt());
        });

        var view = service.create(TENANT_ID, PRODUCT_ID, "claude-3-7-sonnet", "INPUT", "USD", new BigDecimal("3.0000"),
                "MANUAL", CREATOR_ID);

        assertThat(view.providerProductId()).isEqualTo(PRODUCT_ID);
        assertThat(view.modelId()).isEqualTo("claude-3-7-sonnet");
        assertThat(view.tokenType()).isEqualTo("INPUT");
        assertThat(view.currency()).isEqualTo("USD");
        assertThat(view.unitPrice()).isEqualByComparingTo("3.0000");
        assertThat(view.source()).isEqualTo("MANUAL");
        assertThat(view.createdBy()).isEqualTo(CREATOR_ID);
        verify(priceRepository).insert(any(PriceSnapshot.class));
        verify(auditService).record(org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(CREATOR_ID), org.mockito.ArgumentMatchers.eq("PRICE_CREATE"),
                org.mockito.ArgumentMatchers.eq("PRICE_SNAPSHOT"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void rejectsUnknownProduct() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.create(TENANT_ID, PRODUCT_ID, "m", "INPUT", "CNY", BigDecimal.ONE, "MANUAL", CREATOR_ID))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void rejectsUnknownTokenType() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));

        assertThatThrownBy(() -> service.create(TENANT_ID, PRODUCT_ID, "m", "REASONING", "CNY", BigDecimal.ONE,
                "MANUAL", CREATOR_ID)).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("PARAM_INVALID");
    }

    @Test
    void listsLatestSnapshots() {
        var snapshot = new PriceSnapshot(UUID.fromString("0190-0000-0000-0000-000000000030"), PRODUCT_ID, "m",
                PriceTokenType.INPUT, "CNY", BigDecimal.ONE, Instant.now(), "MANUAL", CREATOR_ID, Instant.now());
        when(priceRepository.findAllLatestAt(any(Instant.class))).thenReturn(List.of(snapshot));

        var list = service.listLatest();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).modelId()).isEqualTo("m");
    }

    private static ProviderProduct product() {
        return new ProviderProduct(PRODUCT_ID, UUID.randomUUID(), "deepseek-payg-api", "DeepSeek",
                com.miqroera.miqrokey.domain.model.BillingMode.PAYG, com.miqroera.miqrokey.domain.model.PlanScope.NONE,
                null, null, "[\"messages\"]", "[{\"url\":\"https://api.test.example\"}]", "bearer", "OFFICIAL_API",
                "OFFICIAL_API", com.miqroera.miqrokey.domain.model.BalanceAuthority.OFFICIAL_API,
                com.miqroera.miqrokey.domain.model.ImplementationStatus.IMPLEMENTED, "1", 0, Instant.now(),
                Instant.now());
    }
}
