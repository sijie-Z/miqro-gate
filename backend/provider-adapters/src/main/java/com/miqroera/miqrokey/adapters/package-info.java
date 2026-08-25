/**
 * Concrete vendor adapter implementations.
 *
 * <p>
 * Each adapter implements the {@code provider-spi} contracts for a specific
 * vendor product (Anthropic, OpenAI, DeepSeek, etc.). Adapters are registered
 * at compile time through {@code BuiltInAdapterRegistry} (explicit
 * {@code register} calls in the control plane's configuration — no runtime
 * discovery, no ServiceLoader).
 * </p>
 *
 * <p>
 * This module depends on {@code provider-spi} and Jackson for catalog parsing.
 * It MUST NOT depend on Spring or HTTP client libraries directly — HTTP is
 * always performed through the caller-provided {@code ProviderClient}.
 * </p>
 */
package com.miqroera.miqrokey.adapters;
