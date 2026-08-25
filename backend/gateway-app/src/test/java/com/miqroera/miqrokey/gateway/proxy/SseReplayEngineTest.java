package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.cache.CachedResponse;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for byte-identical cache replay: exact status, filtered
 * headers, content type and raw body — SSE included.
 */
@DisplayName("SseReplayEngine")
class SseReplayEngineTest {

    private final SseReplayEngine engine = new SseReplayEngine();

    private static CachedResponse cached(int status, String contentType, Map<String, List<String>> headers,
            String body) {
        return new CachedResponse(status, contentType, headers, body.getBytes(StandardCharsets.UTF_8),
                TokenBucket.EMPTY, true);
    }

    @Nested
    @DisplayName("Replay")
    class Replay {

        @Test
        @DisplayName("should replay status, headers, content type and body byte-identically")
        void shouldReplayExactResponse() {
            CachedResponse cached = cached(200, "text/event-stream",
                    Map.of("x-request-id", List.of("req_1"), "x-custom", List.of("a", "b")), "data: {\"a\":1}\n\n");
            MockServerHttpResponse response = new MockServerHttpResponse();

            engine.replay(cached, response, "gw-1", "L1").block();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
            assertThat(response.getHeaders().getFirst("x-request-id")).isEqualTo("req_1");
            assertThat(response.getHeaders().get("x-custom")).containsExactly("a", "b");
            assertThat(response.getHeaders().getFirst(SseReplayEngine.X_MIQROKEY_REQUEST_ID)).isEqualTo("gw-1");
            assertThat(response.getHeaders().getFirst(SseReplayEngine.X_MIQROKEY_CACHE)).isEqualTo("L1");
            // MockServerHttpResponse.getBodyAsString() returns Mono<String>.
            assertThat(response.getBodyAsString().block()).isEqualTo("data: {\"a\":1}\n\n");
        }

        @Test
        @DisplayName("should replay non-2xx statuses")
        void shouldReplayErrorStatus() {
            CachedResponse cached = cached(429, "application/json", Map.of(), "{\"error\":\"slow down\"}");
            MockServerHttpResponse response = new MockServerHttpResponse();

            engine.replay(cached, response, "gw-2", "L2").block();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst(SseReplayEngine.X_MIQROKEY_CACHE)).isEqualTo("L2");
            assertThat(response.getBodyAsString().block()).isEqualTo("{\"error\":\"slow down\"}");
        }

        @Test
        @DisplayName("should keep an unparseable stored content type as a raw header")
        void shouldKeepUnparseableContentType() {
            CachedResponse cached = cached(200, "not-a-mime!!!", Map.of(), "x");
            MockServerHttpResponse response = new MockServerHttpResponse();

            engine.replay(cached, response, "gw-3", "L1").block();

            assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("not-a-mime!!!");
            // getBodyAsString() parses the Content-Type header, which is
            // unparseable here, so read the raw body flux instead.
            String body = DataBufferUtils.join(response.getBody()).map(buf -> buf.toString(StandardCharsets.UTF_8))
                    .block();
            assertThat(body).isEqualTo("x");
        }

        @Test
        @DisplayName("should not leak gateway headers from the cached response")
        void shouldNotLeakGatewayHeaders() {
            // A cached response must never carry gateway-only headers; if one
            // slipped into the store, replay must not surface it.
            CachedResponse cached = cached(200, "application/json",
                    Map.of("X-MiQroKey-Cache", List.of("L1"), "X-MiQroKey-Request-Id", List.of("stale")), "{}");
            MockServerHttpResponse response = new MockServerHttpResponse();

            engine.replay(cached, response, "gw-4", "L2").block();

            assertThat(response.getHeaders().getFirst(SseReplayEngine.X_MIQROKEY_CACHE)).isEqualTo("L2");
            assertThat(response.getHeaders().getFirst(SseReplayEngine.X_MIQROKEY_REQUEST_ID)).isEqualTo("gw-4");
        }
    }
}
