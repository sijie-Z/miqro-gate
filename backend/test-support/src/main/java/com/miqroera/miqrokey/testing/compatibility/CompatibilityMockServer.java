package com.miqroera.miqrokey.testing.compatibility;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Standalone Reactor-Netty mock server that responds to CC Switch protocol
 * requests with deterministic synthetic protocol-correct JSON and SSE.
 *
 * <h3>Security contract</h3>
 * <ul>
 * <li>The server never retains request-body bytes, prompt content, tool code,
 * model output, or header <em>values</em> after the request completes.</li>
 * <li>Credential detection checks only case-insensitive header
 * <strong>names</strong>; the corresponding header value is never read, logged,
 * or stored.</li>
 * <li>Request bodies are inspected inside a bounded, transient byte buffer that
 * is discarded as soon as the response has been dispatched.</li>
 * <li>Bodies exceeding {@link #bodyBoundBytes()} receive a safe {@code 413}
 * response whose payload never echoes any part of the request.</li>
 * <li>The JSON diagnostics and observation snapshots intentionally expose only
 * the fields declared on {@link RequestObservation}.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3> The server implements {@link AutoCloseable}. Call
 * {@link #close()} (or use try-with-resources) to release the bound port.
 */
public final class CompatibilityMockServer implements AutoCloseable {

    /**
     * Default maximum number of bytes the server will read from a request body.
     * This is a test-only bound; real deployment should configure it explicitly.
     */
    public static final int DEFAULT_BODY_BOUND_BYTES = 16_384;

    /** Lower-cased header names whose presence indicates a credential header. */
    static final Set<String> CREDENTIAL_HEADER_NAMES = Set.of("x-api-key", "authorization", "api-key", "openai-api-key",
            "x-api-token", "x-auth-token", "x-goog-api-key", "ocp-apim-subscription-key");

    private static final Pattern STREAM_BODY_PATTERN = Pattern.compile("\"stream\"\\s*:\\s*true");

    // --------------------------------------------------------------
    // Deterministic synthetic responses (package-visible for tests)
    // --------------------------------------------------------------

    static final String ANTHROPIC_NON_STREAM_BODY = "{\"id\":\"msg_mock_001\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-sonnet-4-20250514\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"Mock response for CC Switch compatibility testing.\"}],"
            + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":15,\"output_tokens\":8}}";

    static final String ANTHROPIC_SSE_BODY = "event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock_001\",\"type\":\"message\","
            + "\"role\":\"assistant\",\"model\":\"claude-sonnet-4-20250514\",\"content\":[],"
            + "\"usage\":{\"input_tokens\":15,\"output_tokens\":0}}}\n" + "\n" + "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n" + "\n" + "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Mock SSE response for CC Switch testing.\"}}\n" + "\n"
            + "event: content_block_stop\n" + "data: {\"type\":\"content_block_stop\",\"index\":0}\n" + "\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
            + "\"usage\":{\"output_tokens\":8}}\n" + "\n" + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n" + "\n";

    static final String CHAT_NON_STREAM_BODY = "{\"id\":\"chatcmpl-mock-001\",\"object\":\"chat.completion\","
            + "\"created\":1700000000,\"model\":\"gpt-4\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"Mock chat response for CC Switch compatibility testing.\"},"
            + "\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":8,\"total_tokens\":23}}";

    static final String CHAT_SSE_BODY = "data: {\"id\":\"chatcmpl-mock-001\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1700000000,\"model\":\"gpt-4\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n"
            + "\n" + "data: {\"id\":\"chatcmpl-mock-001\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1700000000,\"model\":\"gpt-4\"," + "\"choices\":[{\"index\":0,\"delta\":{\"content\":"
            + "\"Mock SSE chat response for CC Switch testing.\"},\"finish_reason\":null}]}\n" + "\n"
            + "data: {\"id\":\"chatcmpl-mock-001\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1700000000,\"model\":\"gpt-4\","
            + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n" + "\n" + "data: [DONE]\n" + "\n";

    static final String RESPONSES_NON_STREAM_BODY = "{\"id\":\"resp_mock_001\",\"object\":\"response\",\"model\":\"gpt-4o\","
            + "\"output\":[{\"type\":\"message\",\"id\":\"msg_mock_001\",\"status\":\"completed\","
            + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
            + "\"text\":\"Mock response for CC Switch compatibility testing.\",\"annotations\":[]}]}],"
            + "\"usage\":{\"input_tokens\":15,\"output_tokens\":8,\"total_tokens\":23}}";

    static final String RESPONSES_SSE_BODY = "event: response.created\n"
            + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_mock_001\","
            + "\"object\":\"response\",\"model\":\"gpt-4o\",\"output\":[],\"usage\":null}}\n" + "\n"
            + "event: response.output_text.delta\n"
            + "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_mock_001\","
            + "\"output_index\":0,\"content_index\":0,"
            + "\"delta\":\"Mock SSE Responses response for CC Switch testing.\"}\n" + "\n"
            + "event: response.completed\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_mock_001\","
            + "\"object\":\"response\",\"model\":\"gpt-4o\","
            + "\"output\":[{\"type\":\"message\",\"id\":\"msg_mock_001\",\"status\":\"completed\","
            + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
            + "\"text\":\"Mock SSE Responses response for CC Switch testing.\",\"annotations\":[]}]}],"
            + "\"usage\":{\"input_tokens\":15,\"output_tokens\":8,\"total_tokens\":23}}}\n" + "\n";

    // --------------------------------------------------------------
    // Instance state
    // --------------------------------------------------------------

    private final DisposableServer server;
    private final ObservationStore store;
    private final int bodyBoundBytes;
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);
    private final ObjectMapper objectMapper;

    public CompatibilityMockServer(int port, int capacity, int bodyBoundBytes) {
        if (bodyBoundBytes <= 0) {
            throw new IllegalArgumentException("bodyBoundBytes must be positive, was: " + bodyBoundBytes);
        }
        this.store = new ObservationStore(capacity);
        this.bodyBoundBytes = bodyBoundBytes;
        this.objectMapper = new ObjectMapper();
        this.server = HttpServer.create().host("127.0.0.1").port(port).handle(this::handleRequest).bindNow();
    }

    public CompatibilityMockServer(int port, int capacity) {
        this(port, capacity, DEFAULT_BODY_BOUND_BYTES);
    }

    public int getPort() {
        return server.port();
    }

    public ObservationStore getStore() {
        return store;
    }

    public int bodyBoundBytes() {
        return bodyBoundBytes;
    }

    @Override
    public void close() {
        if (!server.isDisposed()) {
            server.disposeNow();
        }
    }

    // --------------------------------------------------------------
    // Top-level dispatch
    // --------------------------------------------------------------

    private Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res) {
        String rawUri = req.uri();
        String path = req.fullPath();
        String method = req.method().name();
        String requestId = "req-" + requestIdCounter.incrementAndGet();
        Instant timestamp = Instant.now();

        Protocol protocol = classifyProtocol(path);
        String contentType = normalizedContentType(req);
        boolean hasCredentialHeader = scanCredentialHeaderNames(req);

        if (protocol == Protocol.DIAGNOSTIC) {
            return handleDiagnostic(req, res, requestId, timestamp, method, rawUri, contentType, hasCredentialHeader);
        }

        return collectBodyBounded(req.receive()).flatMap(bodyBytes -> {
            if (bodyBytes.length > bodyBoundBytes) {
                recordObservation(timestamp, requestId, method, rawUri, protocol, contentType, false,
                        hasCredentialHeader);
                return sendPayloadTooLarge(res, protocol);
            }

            boolean streamFromBody = detectStreamFromBytes(bodyBytes);
            boolean streamQuery = hasStreamQuery(req);
            boolean acceptSSE = isSseAccept(req);
            boolean streaming = streamQuery || acceptSSE || streamFromBody;

            recordObservation(timestamp, requestId, method, rawUri, protocol, contentType, streaming,
                    hasCredentialHeader);

            if (protocol == Protocol.UNKNOWN) {
                return sendNotFound(res);
            }
            if (!"POST".equalsIgnoreCase(method)) {
                return sendMethodNotAllowed(res, protocol);
            }
            return sendProtocolResponse(res, protocol, streaming);
        });
    }

    // --------------------------------------------------------------
    // Protocol classification
    // --------------------------------------------------------------

    static Protocol classifyProtocol(String path) {
        if (path == null) {
            return Protocol.UNKNOWN;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;

        if ("/health".equals(trimmed))
            return Protocol.DIAGNOSTIC;
        if ("/observations".equals(trimmed))
            return Protocol.DIAGNOSTIC;
        if ("/v1/messages".equals(trimmed))
            return Protocol.ANTHROPIC_MESSAGES;
        if ("/v1/chat/completions".equals(trimmed))
            return Protocol.OPENAI_CHAT_COMPLETIONS;
        if ("/v1/responses".equals(trimmed))
            return Protocol.OPENAI_RESPONSES;
        return Protocol.UNKNOWN;
    }

    // --------------------------------------------------------------
    // Credential detection
    // --------------------------------------------------------------

    static boolean scanCredentialHeaderNames(HttpServerRequest req) {
        return req.requestHeaders().names().stream()
                .anyMatch(name -> CREDENTIAL_HEADER_NAMES.contains(name.toLowerCase()));
    }

    // --------------------------------------------------------------
    // Streaming detection
    // --------------------------------------------------------------

    static boolean hasStreamQuery(HttpServerRequest req) {
        String uri = req.uri();
        if (uri == null)
            return false;
        int q = uri.indexOf('?');
        if (q < 0)
            return false;
        String query = uri.substring(q + 1);
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq >= 0 && "stream".equals(param.substring(0, eq))
                    && "true".equalsIgnoreCase(param.substring(eq + 1))) {
                return true;
            }
        }
        return false;
    }

    static boolean isSseAccept(HttpServerRequest req) {
        String accept = req.requestHeaders().get("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    static boolean detectStreamFromBytes(byte[] bodyBytes) {
        if (bodyBytes.length == 0)
            return false;
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        return STREAM_BODY_PATTERN.matcher(body).find();
    }

    // --------------------------------------------------------------
    // Bounded body collector (Repair 1)
    // --------------------------------------------------------------

    /**
     * Collects at most {@code bodyBoundBytes + 1} bytes from a
     * {@code Flux<ByteBuf>} body stream. The returned {@code Mono} emits:
     * <ul>
     * <li>a {@code byte[]} of length {@code <= bodyBoundBytes} when the body fits
     * within the bound, or</li>
     * <li>a {@code byte[]} of length {@code bodyBoundBytes + 1} when the body
     * exceeded the bound (content beyond the first {@code bodyBoundBytes + 1} bytes
     * is discarded and never retained).</li>
     * </ul>
     *
     * <p>
     * The upstream subscription is cancelled as soon as the bound is crossed; every
     * {@code ByteBuf} chunk is released after its readable bytes are consumed. This
     * method never aggregates an unbounded request body in memory.
     * </p>
     *
     * @param bodyFlux
     *            the Netty inbound body chunks
     * @return a Mono that emits the bounded result
     */
    Mono<byte[]> collectBodyBounded(Flux<ByteBuf> bodyFlux) {
        byte[] buffer = new byte[bodyBoundBytes + 1];
        AtomicInteger position = new AtomicInteger(0);
        AtomicBoolean overflow = new AtomicBoolean(false);

        return bodyFlux.handle((buf, sink) -> {
            if (overflow.get()) {
                return;
            }
            int readable = buf.readableBytes();
            int remaining = bodyBoundBytes + 1 - position.get();
            int toRead = Math.min(readable, remaining);
            if (toRead > 0) {
                buf.getBytes(buf.readerIndex(), buffer, position.get(), toRead);
            }
            if (position.addAndGet(toRead) > bodyBoundBytes) {
                overflow.set(true);
                // Immediately complete to cancel the upstream ByteBuf publisher.
                sink.complete();
            }
            // ByteBuf release is handled by Reactor Netty's FluxReceive;
            // we must never release here to avoid double-release.
        }).then(Mono.fromSupplier(() -> {
            int total = position.get();
            if (total > bodyBoundBytes) {
                // Overflow; return an array of length bodyBoundBytes+1 as a sentinel.
                return new byte[bodyBoundBytes + 1];
            }
            return Arrays.copyOf(buffer, total);
        }));
    }

    // --------------------------------------------------------------
    // Media type normalization (Repair 2)
    // --------------------------------------------------------------

    /**
     * Maximum length of a raw {@code Content-Type} header value before it is
     * rejected.
     */
    private static final int MAX_CONTENT_TYPE_LENGTH = 200;

    /**
     * Pattern that every normalized media type must match: {@code type/subtype}
     * where both tokens start with a letter and contain only letters, digits, and
     * common token characters (RFC 7231 subset).
     */
    private static final Pattern MEDIA_TYPE_PATTERN = Pattern
            .compile("[a-z][a-z0-9!#$%&'*+\\-.^_`|~]*/[a-z][a-z0-9!#$%&'*+\\-.^_`|~]*");

    /**
     * Normalizes a raw {@code Content-Type} header value into a safe, bounded media
     * type suitable for storage in observations.
     *
     * <ul>
     * <li>Strips all parameters (everything after the first {@code ;}).</li>
     * <li>Trims whitespace and lowercases.</li>
     * <li>Validates the result matches the {@code type/subtype} token form.</li>
     * <li>Rejects raw values longer than {@value #MAX_CONTENT_TYPE_LENGTH}
     * characters.</li>
     * </ul>
     *
     * <p>
     * Returns an empty string for null, empty, invalid, or oversized input. This
     * ensures observations never contain arbitrary header payloads.
     * </p>
     *
     * @param raw
     *            the raw {@code Content-Type} header value; may be null
     * @return normalized media type (e.g. {@code "application/json"}) or empty
     *         string
     */
    static String normalizeMediaType(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.length() > MAX_CONTENT_TYPE_LENGTH) {
            return "";
        }
        int semi = raw.indexOf(';');
        String mediaType = semi >= 0 ? raw.substring(0, semi) : raw;
        mediaType = mediaType.trim().toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPE_PATTERN.matcher(mediaType).matches()) {
            return "";
        }
        return mediaType;
    }

    /**
     * Extracts the normalized Content-Type from the request, suitable for
     * observations. Never returns the raw header value.
     */
    private static String normalizedContentType(HttpServerRequest req) {
        return normalizeMediaType(req.requestHeaders().get("Content-Type"));
    }

    private void recordObservation(Instant timestamp, String requestId, String method, String rawUri, Protocol protocol,
            String contentType, boolean streaming, boolean hasCredentialHeader) {
        store.record(new RequestObservation(timestamp, requestId, method, rawUri, protocol, contentType, streaming,
                hasCredentialHeader));
    }

    // --------------------------------------------------------------
    // Diagnostic handlers
    // --------------------------------------------------------------

    private Mono<Void> handleDiagnostic(HttpServerRequest req, HttpServerResponse res, String requestId,
            Instant timestamp, String method, String rawUri, String contentType, boolean hasCredentialHeader) {
        String path = req.fullPath();
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;

        if ("/health".equals(trimmed)) {
            if (!"GET".equalsIgnoreCase(method))
                return sendMethodNotAllowed(res, Protocol.DIAGNOSTIC);
            recordObservation(timestamp, requestId, method, rawUri, Protocol.DIAGNOSTIC, contentType, false,
                    hasCredentialHeader);
            return sendHealth(res);
        }
        if ("/observations".equals(trimmed)) {
            if ("GET".equalsIgnoreCase(method)) {
                // Take snapshot before recording so the GET does not self-appear.
                List<RequestObservation> snap = store.snapshot();
                recordObservation(timestamp, requestId, method, rawUri, Protocol.DIAGNOSTIC, contentType, false,
                        hasCredentialHeader);
                return sendObservationsSnapshot(res, snap);
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                recordObservation(timestamp, requestId, method, rawUri, Protocol.DIAGNOSTIC, contentType, false,
                        hasCredentialHeader);
                return sendClearObservations(res);
            }
            return sendMethodNotAllowed(res, Protocol.DIAGNOSTIC);
        }
        return sendNotFound(res);
    }

    private Mono<Void> sendHealth(HttpServerResponse res) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("service", "compatibility-mock");
        body.put("status", "UP");
        res.status(200).header("Content-Type", "application/json");
        return res.sendString(Mono.just(toJson(body))).then();
    }

    /**
     * Builds an explicit, ordered diagnostic DTO list so that Jackson never
     * reflectively serializes {@link RequestObservation} (which contains an
     * {@link Instant} that requires the jsr310 module). Every value is a plain
     * {@code String} or {@code Boolean}.
     */
    private List<Map<String, Object>> toDiagnosticDtos(List<RequestObservation> observations) {
        List<Map<String, Object>> dtos = new ArrayList<>(observations.size());
        for (RequestObservation obs : observations) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("timestamp", obs.timestamp().toString());
            dto.put("requestId", obs.requestId());
            dto.put("httpMethod", obs.httpMethod());
            dto.put("rawUri", obs.rawUri());
            dto.put("protocol", obs.protocol().name());
            dto.put("contentType", obs.contentType());
            dto.put("streamingRequest", obs.streamingRequest());
            dto.put("forbiddenCredentialHeaderReached", obs.forbiddenCredentialHeaderReached());
            dtos.add(dto);
        }
        return dtos;
    }

    private Mono<Void> sendObservationsSnapshot(HttpServerResponse res, List<RequestObservation> snap) {
        res.status(200).header("Content-Type", "application/json");
        return res.sendString(Mono.just(toJson(toDiagnosticDtos(snap)))).then();
    }

    private Mono<Void> sendClearObservations(HttpServerResponse res) {
        store.clear();
        Map<String, Boolean> body = new LinkedHashMap<>();
        body.put("cleared", true);
        res.status(200).header("Content-Type", "application/json");
        return res.sendString(Mono.just(toJson(body))).then();
    }

    // --------------------------------------------------------------
    // Protocol responses
    // --------------------------------------------------------------

    private Mono<Void> sendProtocolResponse(HttpServerResponse res, Protocol protocol, boolean streaming) {
        if (streaming) {
            String body = sseBody(protocol);
            res.status(200).header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
                    .header("Connection", "keep-alive");
            return res.sendString(Mono.just(body)).then();
        }
        String body = nonStreamBody(protocol);
        res.status(200).header("Content-Type", "application/json");
        return res.sendString(Mono.just(body)).then();
    }

    static String nonStreamBody(Protocol protocol) {
        return switch (protocol) {
            case ANTHROPIC_MESSAGES -> ANTHROPIC_NON_STREAM_BODY;
            case OPENAI_CHAT_COMPLETIONS -> CHAT_NON_STREAM_BODY;
            case OPENAI_RESPONSES -> RESPONSES_NON_STREAM_BODY;
            default -> throw new IllegalArgumentException("No response body for protocol: " + protocol);
        };
    }

    static String sseBody(Protocol protocol) {
        return switch (protocol) {
            case ANTHROPIC_MESSAGES -> ANTHROPIC_SSE_BODY;
            case OPENAI_CHAT_COMPLETIONS -> CHAT_SSE_BODY;
            case OPENAI_RESPONSES -> RESPONSES_SSE_BODY;
            default -> throw new IllegalArgumentException("No SSE body for protocol: " + protocol);
        };
    }

    // --------------------------------------------------------------
    // Error responses (safe - never echo any request content)
    // --------------------------------------------------------------

    private Mono<Void> sendNotFound(HttpServerResponse res) {
        res.status(404).header("Content-Type", "application/json");
        return res.sendString(Mono.just("{\"error\":\"not_found\",\"message\":\"Not found\"}")).then();
    }

    private Mono<Void> sendMethodNotAllowed(HttpServerResponse res, Protocol protocol) {
        res.status(405).header("Content-Type", "application/json");
        String body;
        if (protocol == Protocol.ANTHROPIC_MESSAGES) {
            body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    + "\"message\":\"Method not allowed\"}}";
        } else if (protocol == Protocol.OPENAI_RESPONSES || protocol == Protocol.OPENAI_CHAT_COMPLETIONS) {
            body = "{\"error\":{\"message\":\"Method not allowed\"," + "\"type\":\"invalid_request_error\"}}";
        } else {
            body = "{\"error\":\"method_not_allowed\",\"message\":\"Method not allowed\"}";
        }
        return res.sendString(Mono.just(body)).then();
    }

    private Mono<Void> sendPayloadTooLarge(HttpServerResponse res, Protocol protocol) {
        res.status(413).header("Content-Type", "application/json");
        String body;
        if (protocol == Protocol.ANTHROPIC_MESSAGES) {
            body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    + "\"message\":\"Request body exceeds maximum allowed size\"}}";
        } else if (protocol == Protocol.OPENAI_RESPONSES || protocol == Protocol.OPENAI_CHAT_COMPLETIONS) {
            body = "{\"error\":{\"message\":\"Request body exceeds maximum allowed size\","
                    + "\"type\":\"invalid_request_error\"}}";
        } else {
            body = "{\"error\":\"payload_too_large\"," + "\"message\":\"Request body exceeds maximum allowed size\"}";
        }
        return res.sendString(Mono.just(body)).then();
    }

    // --------------------------------------------------------------
    // JSON helpers
    // --------------------------------------------------------------

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }

}
