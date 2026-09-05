package com.miqroera.miqrokey.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback JSON-RPC mock in the MCP streamable-HTTP shape for gateway contract
 * tests: captures every inbound request (method, path, headers of interest and
 * the raw body) and answers with a configurable status/body, so tests can
 * assert byte-identical passthrough, session/header forwarding and status-code
 * copying. Never sees a real upstream and never holds secret material.
 */
public final class McpMockServer implements AutoCloseable {

    private static final String DEFAULT_RESPONSE = "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":1}";

    /** One captured inbound request. */
    public record Request(String method, String path, String sessionId, String authorization, String xApiKey,
            String contentType, byte[] body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile byte[] responseBody = DEFAULT_RESPONSE.getBytes(StandardCharsets.UTF_8);
    private volatile int responseStatus = 200;

    public McpMockServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind loopback mock server", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** Base URL of the mock (e.g. {@code http://127.0.0.1:<port>}). */
    public String getBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Configures the response the mock serves for subsequent requests. */
    public void setResponse(String body, int status) {
        this.responseBody = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        this.responseStatus = status;
    }

    /** Clears captured requests and restores the default 200 result envelope. */
    public void reset() {
        requests.clear();
        responseBody = DEFAULT_RESPONSE.getBytes(StandardCharsets.UTF_8);
        responseStatus = 200;
    }

    public List<Request> capturedRequests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Session-Id"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("x-api-key"),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        byte[] out = responseBody;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
