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
 * The fourth member of the transport-failure family documented in
 * {@code docs/api-contract.md} §7.1: the upstream first-byte deadline
 * ({@code miqrokey.gateway.upstream.first-byte-timeout}), which the gateway
 * does not own as a {@code Mono}/{@code Flux} timeout but hands to
 * reactor-netty as its {@code responseTimeout}
 * ({@code ProxyConfig#proxyWebClient}).
 *
 * <p>
 * The deadline is phase-dependent, and that is the point of this test. Before
 * the provider's response head exists the netty failure arrives wrapped in a
 * {@code WebClientRequestException} and one of the existing clauses maps it.
 * Once the head <em>has</em> been read it surfaces unwrapped, matching no
 * clause of {@code ProxyController}'s deliberately typed chain — so a provider
 * that announced {@code 200} and then stalled rendered as the container's own
 * 500 document, with a stack trace in the log, for a deadline the gateway
 * itself configured (#1375).
 * </p>
 *
 * <p>
 * The deadline is also the one that fires first by default: reactor-netty's
 * read timeout is armed between reads, so for a provider that answers and then
 * stalls it wins over both gateway-owned caps ({@code first-byte-timeout}
 * PT120S, {@code stream-idle-timeout} PT5M, {@code response-timeout} PT10M).
 * The properties below keep that ordering — the short deadline is the netty
 * one.
 * </p>
 *
 * <p>
 * A bare socket is required because the envelope has to be read together with
 * the head that frames it: {@code ProxyController#callUpstreamOnce} copies the
 * provider's response head onto the client response as soon as the head
 * arrives, which happens before the body read stalls. On this deadline the
 * re-framing comes out right — the provider's {@code X-Provider-Marker} is
 * copied while the head closes with the envelope's own
 * {@code content-length: 130} and no {@code content-encoding} — so these
 * assertions pin a behaviour that must not regress. The sibling deadline the
 * gateway owns itself ({@code response-timeout}, whose failure is a
 * {@code java.util.concurrent.TimeoutException}) is the one that does
 * <em>not</em> re-frame; that asymmetry is covered by
 * {@code UpstreamOverallDeadlineFramingIntegrationTest}. Decoded by a test
 * client the difference is invisible, hence {@link RawGatewayClient}.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.upstream.connect-timeout=PT2S",
        "miqrokey.gateway.upstream.first-byte-timeout=PT2S", "miqrokey.gateway.upstream.response-timeout=PT30S",
        "miqrokey.gateway.upstream.stream-idle-timeout=PT30S"})
@Import(GatewayAuthTestConfig.class)
@DisplayName("Upstream first-byte (reactor-netty read) deadline")
class UpstreamFirstByteDeadlineIntegrationTest {

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
    @DisplayName("200 announced then silence: 502 envelope, framed by its own length, not the provider's")
    void envelopeAfterTheHeadIsFramedByItsOwnLength() throws IOException {
        upstream.respondWith(providerHead("Content-Type: application/json\r\nX-Provider-Marker: ph84"), "");
        upstream.holdOpen(15_000L);

        RawGatewayClient.RawResponse raw = RawGatewayClient.callNonStreaming(gatewayPort, READ_TIMEOUT_MILLIS, true);
        System.out.println("[PH84-TEST] first-byte-deadline-after-head " + raw.describe());

        assertThat(raw.statusLine()).as("a gateway-owned deadline is not a gateway crash").contains("502");
        assertThat(raw.body()).as("the deadline maps like its transport siblings").contains("\"type\":\"error\"")
                .contains("upstream_unavailable");
        assertThat(raw.headers()).as("the provider head was copied before the stall, so its headers are on it")
                .containsKey("x-provider-marker");
        assertThat(raw.headers()).as("...including the request id, which the same copy sets")
                .containsKey("x-miqrokey-request-id");
        assertThat(raw.declaredContentLength()).as("the head must describe the bytes that follow it")
                .isEqualTo(raw.bodyBytes());
    }

    @Test
    @DisplayName("the provider's Content-Encoding does not survive onto the gateway's own envelope")
    void envelopeDoesNotInheritTheProviderContentEncoding() throws IOException {
        upstream.respondWith(providerHead("Content-Type: application/json\r\nContent-Encoding: gzip"), "");
        upstream.holdOpen(15_000L);

        RawGatewayClient.RawResponse raw = RawGatewayClient.callNonStreaming(gatewayPort, READ_TIMEOUT_MILLIS, true);
        System.out.println("[PH84-TEST] first-byte-deadline-gzip-announced " + raw.describe());

        assertThat(raw.statusLine()).contains("502");
        assertThat(raw.body()).contains("\"type\":\"error\"");
        assertThat(raw.headers()).as("the envelope is plain UTF-8 JSON whatever the provider announced")
                .doesNotContainKey("content-encoding");
    }

    @Test
    @DisplayName("deadline after a body byte was relayed: no envelope is appended to the committed stream")
    void deadlineAfterARelayedByteAppendsNothing() throws IOException {
        upstream.respondWith(providerHead("Content-Type: application/json"), "{\"id\":\"msg_partial\",");
        upstream.holdOpen(15_000L);

        RawGatewayClient.RawResponse raw = RawGatewayClient.callNonStreaming(gatewayPort, READ_TIMEOUT_MILLIS, true);
        System.out.println("[PH84-TEST] first-byte-deadline-after-relayed-byte " + raw.describe());

        assertThat(raw.statusLine()).as("the relayed head stands: the response is already committed").contains("200");
        assertThat(raw.body()).as("the relayed prefix is the client's only signal, not a spliced envelope")
                .isEqualTo("{\"id\":\"msg_partial\",");
    }

    private static String providerHead(String extraHeaders) {
        return "HTTP/1.1 200 OK\r\n" + extraHeaders + "\r\nContent-Length: " + PROVIDER_ANNOUNCED_LENGTH + "\r\n\r\n";
    }
}
