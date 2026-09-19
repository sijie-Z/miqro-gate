/**
 * Stable Service Provider Interfaces for vendor adapters.
 *
 * <p>
 * This module defines the contracts that every provider adapter must implement.
 * It depends only on {@code com.miqroera.miqrokey.domain} and MUST NOT depend
 * on Spring Framework or any web/HTTP library.
 * </p>
 *
 * <h2>Core SPI</h2>
 * <ul>
 * <li>{@link ProviderProductAdapter} — the one contract a vendor adapter implements</li>
 * <li>{@link AdapterRegistry} — compile-time registry adapters are looked up through</li>
 * </ul>
 *
 * <h2>Contract value types</h2>
 * <p>
 * The rest of this package is the immutable input/output surface of that
 * contract, so an adapter never needs Gateway or control-plane internals:
 * </p>
 * <ul>
 * <li>{@link RouteContext} / {@link InboundRequest} → {@link TargetRequest} — route resolution</li>
 * <li>{@link CredentialMaterial} → {@link CredentialInjection} — credential injection</li>
 * <li>{@link ProviderClient} → {@link CredentialCheck}, {@link ModelCatalogSnapshot},
 * {@link PlanSnapshot} — credential validation, model catalog, plan/balance status</li>
 * <li>{@link UsageContext} → {@link UsageObserver} — per-request usage extraction</li>
 * <li>{@link AdapterCapabilities}, {@link ProtocolFamily} — declared capabilities</li>
 * </ul>
 */
package com.miqroera.miqrokey.spi;
