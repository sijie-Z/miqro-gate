package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A product under a provider with specific protocol, auth, and billing rules.
 */
public record ProviderProduct(UUID id, UUID providerId, String productCode, String displayName, String billingMode,
        String planScope, String credentialTopology, String quotaTopology, String supportedWireProtocols,
        String baseUrlTemplates, String authScheme, String modelCatalogStrategy, String planStatusStrategy,
        String balanceAuthority, String implementationStatus, String catalogVersion, long version, Instant createdAt,
        Instant updatedAt) {
    public static final String BILLING_PAYG = "PAYG";
    public static final String BILLING_FIXED_SUBSCRIPTION = "FIXED_SUBSCRIPTION";
    public static final String BILLING_TOKEN_PACKAGE = "TOKEN_PACKAGE";
    public static final String BILLING_CREDIT_POOL = "CREDIT_POOL";
    public static final String BILLING_HYBRID = "HYBRID";

    public static final String PLAN_NONE = "NONE";
    public static final String PLAN_PERSONAL = "PERSONAL";
    public static final String PLAN_TEAM = "TEAM";
    public static final String PLAN_ENTERPRISE = "ENTERPRISE";

    public static final String TOPOLOGY_SINGLE_SHARED = "SINGLE_SHARED";
    public static final String TOPOLOGY_MULTI_KEY_SHARED_POOL = "MULTI_KEY_SHARED_POOL";
    public static final String TOPOLOGY_PER_SEAT_KEY = "PER_SEAT_KEY";
    public static final String TOPOLOGY_PER_MEMBER_SUBSCRIPTION_KEY = "PER_MEMBER_SUBSCRIPTION_KEY";

    public static final String IMPL_DRAFT = "DRAFT";
    public static final String IMPL_DOCUMENTED = "DOCUMENTED";
    public static final String IMPL_IMPLEMENTED = "IMPLEMENTED";
    public static final String IMPL_VERIFIED = "VERIFIED";
    public static final String IMPL_DEGRADED = "DEGRADED";
    public static final String IMPL_DISABLED = "DISABLED";
}
