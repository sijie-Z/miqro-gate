package com.miqroera.miqrokey.spi;

import reactor.core.publisher.Mono;

/**
 * Bounded HTTP surface the gateway offers to adapters for control-plane
 * operations. Implementations enforce connect/response timeouts, size caps and
 * SSRF rules centrally; adapters never construct their own HTTP clients.
 */
public interface ProviderClient {

    /**
     * Performs one request against the product's base URL. The response body is
     * capped at the control-plane body limit; larger bodies fail with a clear
     * error. Never used for streaming inference traffic.
     */
    Mono<ProviderResponse> exchange(ProviderRequest request);
}
