package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import reactor.core.publisher.Mono;

/**
 * Resolves the upstream credential for an authenticated request: the base URL
 * (from the product catalog, via the route snapshot) and the header value to
 * inject. Implementations must never block the Reactor event loop.
 */
public interface CredentialInjector {

    /**
     * Resolves the upstream target for the request.
     *
     * @return the upstream base URL and the credential header (name + value) to set
     *         on the forwarded request
     * @throws com.miqroera.miqrokey.gateway.vkey.AuthFailureException
     *             502 when the credential cannot be resolved
     */
    Mono<InjectedCredential> resolve(AuthContext ctx);

    /**
     * Upstream target: base URL from the route snapshot's product catalog, and the
     * header to inject. The header value contains the real upstream credential —
     * treat it as sensitive and never log it.
     */
    record InjectedCredential(String baseUrl, String headerName, String headerValue) {
    }
}
