package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HeaderFilters")
class HeaderFiltersTest {

    @Test
    @DisplayName("should strip authorization header")
    void shouldStripAuthorization() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer test-key");
        headers.set("Content-Type", "application/json");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.containsHeader("Authorization")).isFalse();
        assertThat(filtered.getFirst("Content-Type")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("should strip x-api-key header")
    void shouldStripXApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", "test-key");
        headers.set("anthropic-version", "2023-06-01");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.containsHeader("x-api-key")).isFalse();
        assertThat(filtered.getFirst("anthropic-version")).isEqualTo("2023-06-01");
    }

    @Test
    @DisplayName("should strip api-key header")
    void shouldStripApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", "test-key");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.containsHeader("api-key")).isFalse();
    }

    @Test
    @DisplayName("should strip hop-by-hop headers")
    void shouldStripHopByHop() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Connection", "keep-alive");
        headers.set("Transfer-Encoding", "chunked");
        headers.set("anthropic-version", "2023-06-01");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.containsHeader("Connection")).isFalse();
        assertThat(filtered.containsHeader("Transfer-Encoding")).isFalse();
        assertThat(filtered.getFirst("anthropic-version")).isEqualTo("2023-06-01");
    }

    @Test
    @DisplayName("should preserve anthropic-version and anthropic-beta")
    void shouldPreserveAnthropicHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("anthropic-version", "2023-06-01");
        headers.set("anthropic-beta", "prompt-caching-2024-07-31");
        headers.set("Authorization", "Bearer test-key");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.getFirst("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(filtered.getFirst("anthropic-beta")).isEqualTo("prompt-caching-2024-07-31");
        assertThat(filtered.containsHeader("Authorization")).isFalse();
    }

    @Test
    @DisplayName("should strip host header")
    void shouldStripHost() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Host", "evil.example.com");
        headers.set("Content-Type", "application/json");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.containsHeader("Host")).isFalse();
        assertThat(filtered.getFirst("Content-Type")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("should allow custom headers through")
    void shouldAllowCustomHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Custom-Trace-Id", "abc123");
        headers.set("anthropic-version", "2023-06-01");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.getFirst("X-Custom-Trace-Id")).isEqualTo("abc123");
    }

    @Test
    @DisplayName("should strip forged internal and Connection-nominated request headers")
    void shouldStripDynamicAndInternalRequestHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Connection", "keep-alive, X-Remove-Me");
        headers.add("X-Remove-Me", "dynamic-hop-value");
        headers.add("X-MiQroKey-Request-Id", "forged-id");
        headers.add("X-Custom-Trace-Id", "allowed-id");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.asMultiValueMap()).doesNotContainKeys("Connection", "X-Remove-Me", "X-MiQroKey-Request-Id");
        assertThat(filtered.getFirst("X-Custom-Trace-Id")).isEqualTo("allowed-id");
    }

    @Test
    @DisplayName("should strip static and Connection-nominated response headers")
    void shouldFilterResponseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Connection", "X-Upstream-Hop");
        headers.add("X-Upstream-Hop", "remove");
        headers.add("Transfer-Encoding", "chunked");
        headers.add("X-Request-Id", "preserve");

        HttpHeaders filtered = HeaderFilters.filterResponseHeaders(headers);

        assertThat(filtered.asMultiValueMap()).doesNotContainKeys("Connection", "X-Upstream-Hop", "Transfer-Encoding");
        assertThat(filtered.getFirst("X-Request-Id")).isEqualTo("preserve");
    }

    @Test
    @DisplayName("should strip the X-Miqro-* claim namespace but keep session-observation headers")
    void shouldStripCaaClaimHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Miqro-Project-Id", "9f0e2f52-0000-0000-0000-000000000001");
        headers.set("X-Miqro-Claim-Source", "tool_path");
        headers.set("X-Miqro-Activity", "act-1");
        headers.set("X-Claude-Code-Session-Id", "sess-1");
        headers.set("anthropic-version", "2023-06-01");

        HttpHeaders filtered = HeaderFilters.filterInboundHeaders(headers);

        assertThat(filtered.containsHeader("X-Miqro-Project-Id")).isFalse();
        assertThat(filtered.containsHeader("X-Miqro-Claim-Source")).isFalse();
        assertThat(filtered.containsHeader("X-Miqro-Activity")).isFalse();
        assertThat(filtered.getFirst("X-Claude-Code-Session-Id")).isEqualTo("sess-1");
        assertThat(filtered.getFirst("anthropic-version")).isEqualTo("2023-06-01");
    }
}
