package com.miqroera.miqrokey.controlplane.client;

import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.miqroera.miqrokey.spi.ProviderRequest;
import com.miqroera.miqrokey.spi.ProviderResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HttpProviderClient (G3.1)")
class HttpProviderClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private int port;
    private final AtomicReference<RequestCapture> lastRequest = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            lastRequest.set(new RequestCapture(exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), copyHeaders(exchange)));
            handle(exchange);
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** Overridden per test; default answers 404. */
    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = "not-found".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(404, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
    @Test
    @DisplayName("sends the credential header and returns status/body/headers")
    void exchangesWithCredentialInjection() {
        handler(exchange -> respond(exchange, 200, "{\"data\":[]}", Map.of("Content-Type", "application/json")));

        ProviderResponse response = client().exchange(ProviderRequest.get("/models")).block();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"data\":[]}");
        assertThat(response.headers()).containsKey("content-type");
        RequestCapture captured = lastRequest.get();
        assertThat(captured.path()).isEqualTo("/api/models"); // base path + request path
        assertThat(captured.query()).isNull();
        assertThat(captured.headers()).containsEntry("authorization", List.of("Bearer sk-test"));
        assertThat(captured.headers()).containsEntry("accept", List.of("application/json"));
    }

    @Test
    @DisplayName("concatenates the request query into the target URI")
    void forwardsQuery() {
        handler(exchange -> respond(exchange, 200, "{}", Map.of()));

        client().exchange(ProviderRequest.get("/models", "a=b&c=d")).block();

        assertThat(lastRequest.get().path()).isEqualTo("/api/models");
        assertThat(lastRequest.get().query()).isEqualTo("a=b&c=d");
    }

    @Test
    @DisplayName("surfaces a non-2xx response as-is and never follows redirects")
    void returnsRedirectWithoutFollowing() {
        handler(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.com/evil");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        ProviderResponse response = client().exchange(ProviderRequest.get("/models")).block();

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers()).containsEntry("location", List.of("http://example.com/evil"));
    }

    @Test
    @DisplayName("aborts when the overall request deadline is exceeded")
    void enforcesRequestTimeout() {
        handler(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late", Map.of());
        });
        HttpProviderClient client = new HttpProviderClient(URI.create("http://127.0.0.1:" + port + "/api"),
                "Authorization", "Bearer sk-test", loopbackValidator(), Duration.ofSeconds(2), Duration.ofMillis(200),
                1024);

        assertThatThrownBy(() -> client.exchange(ProviderRequest.get("/models")).block())
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @DisplayName("caps the response body at the configured limit")
    void enforcesResponseBodyLimit() {
        String big = "x".repeat(4096);
        handler(exchange -> respond(exchange, 200, big, Map.of()));
        HttpProviderClient client = new HttpProviderClient(URI.create("http://127.0.0.1:" + port + "/api"),
                "Authorization", "Bearer sk-test", loopbackValidator(), Duration.ofSeconds(2), Duration.ofSeconds(5),
                1024);

        assertThatThrownBy(() -> client.exchange(ProviderRequest.get("/models")).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds the control-plane body limit");
    }

    @Test
    @DisplayName("rejects non-allow-listed targets at construction before any request is sent")
    void rejectsTargetOutsideAllowlist() {
        int before = requestCount.get();
        // Production default: empty allowlist — even loopback http is denied.
        assertThatThrownBy(() -> new HttpProviderClient(URI.create("http://127.0.0.1:" + port + "/api"),
                "Authorization", "Bearer sk-test", new UpstreamTargetValidator(List.of()), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 1024)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Upstream target is not allowed");
        assertThat(requestCount.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("pins the connection to the validated address resolved at construction")
    void pinsConnectionToValidatedAddress() throws Exception {
        // localhost resolves to a loopback family on every platform; both are
        // allowlisted so whichever address comes first is accepted.
        HttpProviderClient client = new HttpProviderClient(URI.create("http://localhost:" + port + "/api"),
                "Authorization", "Bearer sk-test",
                new UpstreamTargetValidator(List.of("127.0.0.0/8", "::1/128")), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 1024);

        assertThat(client.pinnedBaseUri().getHost()).isNotEqualTo("localhost");
        assertThat(InetAddress.getByName(client.pinnedBaseUri().getHost()).isLoopbackAddress()).isTrue();
        assertThat(client.pinnedBaseUri().getPort()).isEqualTo(port);
        assertThat(client.pinnedBaseUri().getPath()).isEqualTo("/api");
        // Plain http: no TLS layer, no SNI parameters.
        assertThat(client.sslParameters()).isNull();
    }

    @Test
    @DisplayName("https clients keep endpoint identification active on the pinned IP literal")
    void keepsEndpointIdentificationForHttps() {
        HttpProviderClient client = new HttpProviderClient(URI.create("https://127.0.0.1:" + port + "/api"),
                "Authorization", "Bearer sk-test", new UpstreamTargetValidator(List.of("127.0.0.0/8")),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 1024);

        // An IP-literal host has no SNI name; certificate identity must still
        // be checked against the literal (HTTPS algorithm).
        assertThat(client.sslParameters()).isNotNull();
        assertThat(client.sslParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(client.sslParameters().getServerNames()).isNullOrEmpty();
        assertThat(client.pinnedBaseUri().getHost()).isEqualTo("127.0.0.1");
    }

    private HttpProviderClient client() {
        return new HttpProviderClient(URI.create("http://127.0.0.1:" + port + "/api"), "Authorization",
                "Bearer sk-test", loopbackValidator(), Duration.ofSeconds(2), Duration.ofSeconds(5), 1024 * 1024);
    }

    private static UpstreamTargetValidator loopbackValidator() {
        return new UpstreamTargetValidator(List.of("127.0.0.0/8"));
    }

    private static Map<String, List<String>> copyHeaders(HttpExchange exchange) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> copy.put(key.toLowerCase(), List.copyOf(values)));
        return copy;
    }

    private void handler(ExchangeHandler exchangeHandler) {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            lastRequest.set(new RequestCapture(exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), copyHeaders(exchange)));
            exchangeHandler.handle(exchange);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body, Map<String, String> headers)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        headers.forEach(exchange.getResponseHeaders()::add);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record RequestCapture(String path, String query, Map<String, List<String>> headers) {
    }
}
