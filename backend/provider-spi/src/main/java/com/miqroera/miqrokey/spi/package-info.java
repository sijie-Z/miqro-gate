/**
 * Stable Service Provider Interfaces for vendor adapters.
 *
 * <p>
 * This module defines the contracts that every provider adapter must implement.
 * It depends only on {@code com.miqroera.miqrokey.domain} and MUST NOT depend
 * on Spring Framework or any web/HTTP library.
 * </p>
 *
 * <h2>Core SPIs</h2>
 * <ul>
 * <li>{@code ProviderProductAdapter} — entry point for a vendor product</li>
 * <li>{@code CredentialInjector} — injects real upstream credentials</li>
 * <li>{@code PathPolicy} — validates and rewrites request paths</li>
 * <li>{@code ModelCatalogProvider} — provides available models</li>
 * <li>{@code UsageParser} — extracts token usage from responses</li>
 * <li>{@code PlanStatusProvider} — queries plan/balance status</li>
 * <li>{@code CredentialValidator} — validates upstream credentials</li>
 * </ul>
 */
package com.miqroera.miqrokey.spi;
