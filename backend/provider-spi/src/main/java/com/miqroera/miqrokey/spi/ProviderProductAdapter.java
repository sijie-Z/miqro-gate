package com.miqroera.miqrokey.spi;

import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * The stable provider-adapter contract
 * ({@code docs/provider-adapter-contract.md §2}). One adapter serves exactly
 * one {@link ProviderProductDefinition}; adapters are registered at compile
 * time through {@link AdapterRegistry} and never loaded from remote sources.
 *
 * <p>
 * The core Gateway depends only on this SPI — no adapter code, no
 * {@code if (vendor == ...)} branches.
 */
public interface ProviderProductAdapter {

    /** Stable adapter id; must equal the catalog's {@code adapterId}. */
    String adapterId();

    /** Protocol families this adapter can serve. */
    Set<ProtocolFamily> protocols();

    /**
     * Turns the validated route decision plus the inbound request into the exact
     * upstream target. Must not perform I/O; returns a pure value.
     */
    TargetRequest resolve(RouteContext route, InboundRequest request);

    /**
     * Declares how the upstream credential is injected and which inbound auth
     * headers are stripped.
     */
    CredentialInjection credentialInjection(CredentialMaterial credential);

    /**
     * Checks the credential against the provider without side effects.
     * Implementations use {@link ProviderClient}; the gateway never stores the
     * result as a credential replacement.
     */
    Mono<CredentialCheck> validateCredential(ProviderClient client);

    /** Fetches the product's model catalog via the provider's official API. */
    Mono<ModelCatalogSnapshot> fetchModels(ProviderClient client);

    /**
     * Creates a per-request usage observer. Called once per request before the
     * upstream exchange; the observer reads the response stream in parallel and
     * must never alter bytes or event order.
     */
    UsageObserver createUsageObserver(UsageContext context);

    /**
     * Fetches plan/balance status for a subscription. May resolve to an
     * {@code UNAVAILABLE} snapshot when the product has no official API.
     */
    Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription);

    /** Declared capabilities used by the control plane UI and jobs. */
    AdapterCapabilities capabilities();
}
