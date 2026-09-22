package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two deadlines the gateway owns itself, applied to an upstream that has
 * not yet produced a body byte: the overall hard deadline
 * ({@code miqrokey.gateway.upstream.response-timeout}) and the stream-idle
 * deadline ({@code miqrokey.gateway.upstream.stream-idle-timeout}).
 *
 * <p>
 * Both are plain {@code Mono}/{@code Flux} timeouts, so both fail with
 * {@code java.util.concurrent.TimeoutException} — neither a
 * {@code WebClientRequestException} nor a {@code PrematureCloseException}. With
 * no clause for it the exception escaped {@code ProxyController}'s typed
 * {@code onErrorResume} chain and the container rendered its own 500 error
 * document: a gateway-side deadline reached the client as an apparent gateway
 * crash, without {@code X-MiQroKey-Request-Id} and in a shape no inference
 * client parses. Nothing has been relayed downstream at that point, so the
 * protocol envelope is still writable and the deadline maps exactly like its
 * transport siblings.
 * </p>
 *
 * <p>
 * The last case is the boundary: once a body byte has been relayed the response
 * is committed and appending an envelope would corrupt the stream already on
 * the wire, so the deadline must be propagated unchanged.
 * </p>
 *
 * <p>
 * The ordering between the two deadlines is what makes each case reachable:
 * stream-idle (1s here, 5m by default) is shorter than the overall cap (3s
 * here, 10m by default), so a provider that answers and then stalls hits idle;
 * a provider that never finishes its status line can only hit the overall cap.
 * The {@code first-byte-timeout} is left long deliberately — it is
 * reactor-netty's {@code ReadTimeoutException}, a different type with its own
 * existing mapping.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.upstream.connect-timeout=PT2S",
        "miqrokey.gateway.upstream.first-byte-timeout=PT10S", "miqrokey.gateway.upstream.response-timeout=PT3S",
        "miqrokey.gateway.upstream.stream-idle-timeout=PT1S"})
@AutoConfigureWebTestClient
@Import(GatewayAuthTestConfig.class)
@DisplayName("Gateway-owned deadlines before the first relayed body byte")
class UpstreamDeadlineEnvelopeIntegrationTest {

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

    private EntityExchangeResult<byte[]> call() {
        return webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange()
                .expectBody().returnResult();
    }

    private static String describe(EntityExchangeResult<byte[]> result) {
        String body = result.getResponseBody() == null
                ? "<empty>"
                : new String(result.getResponseBody(), StandardCharsets.UTF_8);
        return "status=" + result.getStatus() + " content-type=" + result.getResponseHeaders().getContentType()
                + " body=[" + body + "]";
    }

    @Test
    @DisplayName("status line never completed + overall deadline: 502 upstream_unavailable envelope")
    void overallDeadlineBeforeTheResponseHead() {
        // A partial status line, then the socket is held open: reactor-netty has
        // decoded no response head, so only the overall deadline can end this.
        upstream.respondWith("HTTP/1.1 200 OK\r\n", "");
        upstream.holdOpen(8000L);

        EntityExchangeResult<byte[]> result = call();
        System.out.println("[PH84-TEST] overall-deadline-no-head -> " + describe(result));

        assertThat(result.getStatus().value()).isEqualTo(502);
        assertThat(result.getResponseHeaders().getContentType()).hasToString("application/json");
        assertThat(describe(result)).contains("\"type\":\"error\"").contains("upstream_unavailable")
                .contains("\"message\"");
    }

    @Test
    @DisplayName("200 announced then the body stalls + stream-idle deadline: same envelope, no 500 document")
    void idleDeadlineAfterTheResponseHead() {
        upstream.respondWith("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n", "");
        upstream.holdOpen(6000L);

        EntityExchangeResult<byte[]> result = call();
        System.out.println("[PH84-TEST] idle-deadline-after-head -> " + describe(result));

        assertThat(result.getStatus().value()).isEqualTo(502);
        assertThat(describe(result)).contains("\"type\":\"error\"").contains("upstream_unavailable");
    }

    @Test
    @DisplayName("deadline after a body byte was relayed: no envelope is appended to the committed stream")
    void deadlineAfterTheFirstRelayedByteAppendsNothing() {
        upstream.respondWith("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n",
                "{\"id\":\"msg_partial\",\"type\":\"message\",\"content\":[");
        upstream.holdOpen(6000L);

        String outcome;
        try {
            outcome = describe(call());
        } catch (Exception e) {
            // The response is committed, so the client observes a broken
            // exchange rather than an error document.
            outcome = "client-observed-error: " + e.getMessage();
        }
        System.out.println("[PH84-TEST] idle-deadline-after-first-byte -> " + outcome);

        assertThat(outcome).doesNotContain("\"type\":\"error\"").doesNotContain("upstream_unavailable");
    }
}
