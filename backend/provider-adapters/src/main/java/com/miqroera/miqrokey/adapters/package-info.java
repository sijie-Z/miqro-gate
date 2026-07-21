/**
 * Concrete vendor adapter implementations.
 *
 * <p>
 * Each adapter implements the {@code provider-spi} contracts for a specific
 * vendor product (Anthropic, OpenAI, DeepSeek, etc.). Adapters are discovered
 * at compile time and registered via Java ServiceLoader.
 * </p>
 *
 * <p>
 * This module depends on {@code provider-spi} and Jackson for catalog parsing.
 * It MUST NOT depend on Spring or HTTP client libraries directly — credential
 * injection and HTTP calls are coordinated by {@code gateway-app}.
 * </p>
 */
package com.miqroera.miqrokey.adapters;
