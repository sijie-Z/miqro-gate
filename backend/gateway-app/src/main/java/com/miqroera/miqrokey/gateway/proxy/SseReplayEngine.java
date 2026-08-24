package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.cache.CachedResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Replays a cached response byte-identically: exact status, filtered headers,
 * content type, and raw body — including SSE streams, whose byte sequence must
 * be preserved for clients that parse events. The only headers added are the
 * gateway's own {@code X-MiQroKey-*} tracking headers.
 */
@Component
public class SseReplayEngine {

    public static final String X_MIQROKEY_REQUEST_ID = "X-MiQroKey-Request-Id";
    public static final String X_MIQROKEY_CACHE = "X-MiQroKey-Cache";

    /**
     * Writes the cached response to the client.
     *
     * @param cached
     *            the stored response
     * @param response
     *            the downstream response
     * @param requestId
     *            this request's gateway id
     * @param hitLevel
     *            {@code "L1"} or {@code "L2"} for the cache header
     */
    public Mono<Void> replay(CachedResponse cached, ServerHttpResponse response, String requestId, String hitLevel) {
        response.setStatusCode(HttpStatusCode.valueOf(cached.statusCode()));
        response.getHeaders().clear();
        cached.headers().forEach((name, values) -> response.getHeaders().put(name, values));
        if (cached.contentType() != null) {
            try {
                response.getHeaders().setContentType(MediaType.parseMediaType(cached.contentType()));
            } catch (IllegalArgumentException ignored) {
                // Unparseable stored content type: emit the raw header so the
                // value is never lost on replay.
                response.getHeaders().set(HttpHeaders.CONTENT_TYPE, cached.contentType());
            }
        }
        response.getHeaders().set(X_MIQROKEY_REQUEST_ID, requestId);
        response.getHeaders().set(X_MIQROKEY_CACHE, hitLevel);
        byte[] body = cached.body();
        return response.writeWith(Flux.just(response.bufferFactory().wrap(body)));
    }
}
