package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway-owned sibling of
 * {@code UpstreamFirstByteDeadlineIntegrationTest}: the overall cap
 * {@code miqrokey.gateway.upstream.response-timeout}, applied by
 * {@code ProxyController} itself as a {@code Mono} timeout.
 *
 * <p>
 * Its failure is a {@code java.util.concurrent.TimeoutException}, which shares
 * no closer common ancestor with reactor-netty's {@code ReadTimeoutException}
 * than {@code Throwable} — the two deadlines reach the typed clause chain by
 * different types, which is why the clause covering both takes the throwable as
 * an argument.
 * </p>
 *
 * <p>
 * The interest of this file is not the envelope's <em>content</em> — both
 * deadlines write the same one through the same {@code writeError} — but the
 * <em>head</em> that carries it. {@code ProxyController#callUpstreamOnce}
 * copies the provider's response head onto the client response as soon as that
 * head arrives, and this deadline fires after it: the envelope is 130 bytes
 * while the head still declares the provider's {@code Content-Length: 1000}, so
 * a real client (curl, any SDK) blocks until its own read timeout waiting for
 * 870 bytes that no one will send. A hanging request is worse than the
 * container 500 it replaced, and a decoding test client cannot see it, so these
 * tests read the wire through {@link RawGatewayClient}.
 * </p>
 *
 * <p>
 * The properties below keep the overall cap short and the netty deadline out of
 * the way, the inverse of the sibling test's ordering.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.upstream.connect-timeout=PT2S",
        "miqrokey.gateway.upstream.first-byte-timeout=PT10S", "miqrokey.gateway.upstream.response-timeout=PT2S",
        "miqrokey.gateway.upstream.stream-idle-timeout=PT30S"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("Upstream overall (gateway-owned) deadline: the head it writes over the provider's")
class UpstreamOverallDeadlineFramingIntegrationTest {

    /** What the provider promises in its head, and then never delivers. */
    private static final int PROVIDER_ANNOUNCED_LENGTH = 1000;

    /** How long the raw client waits for body bytes after reading the head. */
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private static final RawUpstreamStub upstream = startUpstream();

    private static RawUpstreamStub startUpstream() {
        try {
            return new RawUpstreamStub();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", upstream::baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    @Test
    @DisplayName("200 announced then silence: the 502 envelope is framed by its own length, not the provider's")
    void envelopeAfterTheHeadIsFramedByItsOwnLength() throws IOException {
        upstream.respondWith(providerHead("Content-Type: application/json"), "");
        upstream.holdOpen(15_000L);

        RawGatewayClient.RawResponse raw = RawGatewayClient.callNonStreaming(gatewayPort, READ_TIMEOUT_MILLIS, true);
        System.out.println("[PH84-TEST] overall-deadline-after-head " + raw.describe());

        assertThat(raw.statusLine()).as("a gateway-owned deadline is not a gateway crash").contains("502");
        assertThat(raw.body()).as("the deadline maps like its transport siblings").contains("\"type\":\"error\"")
                .contains("upstream_unavailable");
        assertThat(raw.headers()).as("the copied head carries the request id the envelope belongs to")
                .containsKey("x-miqrokey-request-id");
        assertThat(raw.declaredContentLength())
                .as("the head must describe the bytes that follow it, not the provider's %d", PROVIDER_ANNOUNCED_LENGTH)
                .isEqualTo(raw.bodyBytes());
    }

    @Test
    @DisplayName("the provider's Content-Encoding does not survive onto the gateway's own envelope")
    void envelopeDoesNotInheritTheProviderContentEncoding() throws IOException {
        upstream.respondWith(providerHead("Content-Type: application/json\r\nContent-Encoding: gzip"), "");
        upstream.holdOpen(15_000L);

        RawGatewayClient.RawResponse raw = RawGatewayClient.callNonStreaming(gatewayPort, READ_TIMEOUT_MILLIS, true);
        System.out.println("[PH84-TEST] overall-deadline-gzip-announced " + raw.describe());

        assertThat(raw.statusLine()).contains("502");
        assertThat(raw.body()).contains("\"type\":\"error\"");
        assertThat(raw.headers()).as("the envelope is plain UTF-8 JSON whatever the provider announced")
                .doesNotContainKey("content-encoding");
    }

    private static String providerHead(String extraHeaders) {
        return "HTTP/1.1 200 OK\r\n" + extraHeaders + "\r\nContent-Length: " + PROVIDER_ANNOUNCED_LENGTH + "\r\n\r\n";
    }
}
