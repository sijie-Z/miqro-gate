package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A product under a provider with specific protocol, auth, and billing rules.
 */
public record ProviderProduct(UUID id, UUID providerId, String productCode, String displayName, BillingMode billingMode,
        PlanScope planScope, CredentialTopology credentialTopology, QuotaTopology quotaTopology,
        String supportedWireProtocols, String baseUrlTemplates, String authScheme, String modelCatalogStrategy,
        String planStatusStrategy, BalanceAuthority balanceAuthority, ImplementationStatus implementationStatus,
        String catalogVersion, long version, Instant createdAt, Instant updatedAt) {
}
