package com.miqroera.miqrokey.gateway.proxy;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1447: the retry predicate of {@code doForward} must not treat the transport
 * connect deadline as a retryable connection failure. On a blackholed upstream
 * <em>every</em> attempt ends in {@link ConnectTimeoutException}, so retrying
 * there doubled the client's wait (measured on the demo stack: 12 ×
 * connect-timeout = 120.15s at PT10S, 24.9s at PT2S).
 * {@code docs/provider-adapter-contract.md} §8 allows the single safe retry
 * only for a connection-phase failure that is "非任何超时" — any timeout follows the
 * deadline semantics instead.
 *
 * <p>
 * The pair below is the point of the test: a plain {@link ConnectException}
 * ("connection refused", the failure the retry exists for) must stay retryable,
 * while its {@link ConnectTimeoutException} subclass — same exception family,
 * different cause — must not.
 * </p>
 */
@DisplayName("G2.5 connection-phase retry predicate")
class RetryableConnectionFailureTest {

    private static final URI UPSTREAM = URI.create("https://upstream.invalid/v1/messages");

    private static WebClientRequestException webClientFailure(Throwable cause) {
        return new WebClientRequestException(cause, HttpMethod.POST, UPSTREAM, HttpHeaders.EMPTY);
    }

    @Test
    @DisplayName("a connect deadline is not retried: the retry window is one round of addresses, not two")
    void connectTimeoutIsNotRetried() {
        assertThat(ProxyController.retryableConnectionFailure(
                webClientFailure(new ConnectTimeoutException("connection timed out: upstream.invalid/1.2.3.4:443")),
                false)).isFalse();
    }

    @Test
    @DisplayName("connection refused is still retried once (the failure the retry exists for)")
    void connectionRefusedIsRetried() {
        assertThat(ProxyController
                .retryableConnectionFailure(webClientFailure(new ConnectException("Connection refused")), false))
                .isTrue();
    }

    @Test
    @DisplayName("the other transport timeouts are not retried either")
    void otherTimeoutsAreNotRetried() {
        assertThat(ProxyController.retryableConnectionFailure(webClientFailure(ReadTimeoutException.INSTANCE), false))
                .isFalse();
        assertThat(ProxyController.retryableConnectionFailure(
                webClientFailure(new java.util.concurrent.TimeoutException("deadline")), false)).isFalse();
        assertThat(ProxyController
                .retryableConnectionFailure(webClientFailure(new SocketTimeoutException("read timed out")), false))
                .isFalse();
    }

    @Test
    @DisplayName("once a first byte was observed nothing is retried, and non-transport failures never are")
    void firstByteAndNonTransportFailuresAreNotRetried() {
        assertThat(ProxyController
                .retryableConnectionFailure(webClientFailure(new ConnectException("Connection refused")), true))
                .isFalse();
        assertThat(
                ProxyController.retryableConnectionFailure(new IllegalStateException("not a transport failure"), false))
                .isFalse();
    }
}
