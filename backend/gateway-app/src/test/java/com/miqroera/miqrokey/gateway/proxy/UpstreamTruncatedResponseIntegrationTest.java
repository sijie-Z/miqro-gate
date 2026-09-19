package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upstream half-open behaviour: the provider accepts the request, emits a
 * response status line (and optionally part of the body), then drops the
 * connection before the announced framing is satisfied.
 *
 * <p>
 * This is hunting zone #3 and no existing test covered it.
 * {@code AnthropicMockProvider.disconnectNextRequest()} closes the channel
 * <em>before</em> any response byte — a connection-phase failure that maps onto
 * the {@code WebClientRequestException} clause. A close <em>after</em> the
 * status line takes a different path through reactor-netty: the response head
 * has been decoded but <em>nothing has been relayed downstream yet</em> — the
 * server response is still uncommitted and Spring rolls back the copied
 * upstream headers on the error path — and the body flux fails with
 * {@code PrematureCloseException}, an {@code IOException}, neither a
 * {@code WebClientRequestException} nor a timeout. It therefore matched no
 * clause and escaped to the container, which rendered its own 500 error
 * document instead of the protocol envelope.
 * </p>
 *
 * <p>
 * The wire is driven by {@link RawUpstreamStub} rather than an HTTP server
 * framework, because a conforming server will not emit a body shorter than its
 * own {@code Content-Length}.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("Upstream truncation after the status line")
class UpstreamTruncatedResponseIntegrationTest {

    private static final RawUpstreamStub upstream = startUpstream();

    private static RawUpstreamStub startUpstream() {
        try {
            return new RawUpstreamStub();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", upstream::baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    @AfterEach
    void resetUpstream() {
        upstream.respondWith("", "");
        upstream.holdOpen(250L);
    }

    private EntityExchangeResult<byte[]> call() {
        return webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectBody().returnResult();
    }

    private static String describe(EntityExchangeResult<byte[]> result) {
        String body = result.getResponseBody() == null
                ? "<empty>"
                : new String(result.getResponseBody(), StandardCharsets.UTF_8);
        return "status=" + result.getStatus() + " content-type=" + result.getResponseHeaders().getContentType()
                + " content-length=" + result.getResponseHeaders().getContentLength() + " body=[" + body + "]";
    }

    @Test
    @DisplayName("status line then close with zero body bytes: documented mapping is 502 upstream_unavailable")
    void upstreamDiesAfterStatusLineBeforeAnyBodyByte() {
        // 200 OK + Content-Length: 200 announced, then the socket closes with no
        // body byte ever written — the earliest point an upstream can die after
        // having accepted the request.
        upstream.respondWith("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 200\r\n\r\n", "");
        int connectionsBefore = upstream.connections();

        EntityExchangeResult<byte[]> result = call();
        String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        System.out.println("[PH31-PROBE] status-line-then-close-no-body -> " + describe(result)
                + " upstream-connections=" + (upstream.connections() - connectionsBefore));

        assertThat(result.getStatus().value()).isEqualTo(502);
        assertThat(result.getResponseHeaders().getContentType()).hasToString("application/json");
        assertThat(body).contains("\"type\":\"error\"").contains("upstream_unavailable").contains("\"message\"");
        // The request reached the upstream exactly once: a close after the status
        // line is not a connection-phase failure and must not be retried blindly —
        // the request may already have been processed and billed upstream.
        assertThat(upstream.connections() - connectionsBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("status line + partial body then close: the truncated framing must never read as a clean success")
    void upstreamDiesAfterPartialBody() {
        upstream.respondWith("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 200\r\n\r\n",
                "{\"id\":\"msg_partial\",\"type\":\"message\",\"content\":[");

        String outcome;
        try {
            outcome = describe(call());
        } catch (Exception e) {
            // Once a body byte has been relayed the downstream response is
            // committed, so no error envelope can follow it: the connection is torn
            // down instead, which the client observes as a broken exchange.
            outcome = "client-observed-error: " + e.getMessage();
        }
        System.out.println("[PH31-PROBE] status-line-then-partial-body-then-close -> " + outcome);

        // Both outcomes are acceptable; a successful-looking reply is not.
        assertThat(outcome).doesNotContain("\"stop_reason\"").doesNotContain("\"usage\":");
    }

    @Test
    @DisplayName("chunked status line, one chunk, then close: same rule, no fabricated success")
    void upstreamDiesMidChunkedBody() {
        upstream.respondWith("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n",
                "24\r\n{\"id\":\"msg_partial\",\"type\":\"message\",\r\n");

        String outcome;
        try {
            outcome = describe(call());
        } catch (Exception e) {
            outcome = "client-observed-error: " + e.getMessage();
        }
        System.out.println("[PH31-PROBE] chunked-then-close -> " + outcome);

        assertThat(outcome).doesNotContain("\"stop_reason\"").doesNotContain("\"usage\":");
    }
}
