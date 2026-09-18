package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the {@code text/event-stream} half of MCP Streamable HTTP (#786).
 *
 * <p>
 * The spec lets the server answer a JSON-RPC request with either
 * {@code application/json} or {@code text/event-stream}, and real servers pick
 * the stream. A stream-framed reply carries the message on {@code data:} lines,
 * so parsing the body as bare JSON dies on the first {@code event:} field
 * instead of reading the answer that is right there.
 * </p>
 *
 * <p>
 * Only what a one-shot request needs is implemented: the first message carrying
 * a {@code result} or an {@code error} is the answer, a parseable notification
 * is kept merely as a fallback, comments and other field names are ignored, and
 * multi-line data is joined per the event-stream wire format.
 * </p>
 */
final class SseJsonRpc {

    private SseJsonRpc() {
    }

    /**
     * Whether the body is stream-framed: an event-stream field name (or the comment
     * line a keep-alive uses) can only start a frame, never a JSON document. Read
     * from the body rather than the {@code Content-Type} so a server that mislabels
     * or omits the header still gets its framing honoured.
     */
    static boolean looksFramed(byte[] body) {
        for (String line : lines(body)) {
            if (line.isEmpty()) {
                continue;
            }
            return line.startsWith("data:") || line.startsWith("event:") || line.startsWith(":");
        }
        return false;
    }

    /**
     * The first message carrying {@code result} or {@code error}; {@code null} when
     * the stream holds no parseable message at all.
     */
    static JsonNode firstMessage(byte[] body, ObjectMapper mapper) {
        StringBuilder data = new StringBuilder();
        JsonNode fallback = null;
        for (String line : lines(body)) {
            if (line.isEmpty()) {
                JsonNode message = parse(data, mapper);
                if (message != null) {
                    if (message.has("result") || message.has("error")) {
                        return message;
                    }
                    fallback = fallback == null ? message : fallback;
                }
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue; // comment, routinely used as a keep-alive
            }
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(stripOneLeadingSpace(line.substring("data:".length())));
            }
            // `event:`, `id:` and `retry:` carry no payload of their own
        }
        // A frame is only terminated by a blank line, so the last one may still
        // be buffered when the stream simply ends.
        JsonNode trailing = parse(data, mapper);
        if (trailing != null && (trailing.has("result") || trailing.has("error"))) {
            return trailing;
        }
        return fallback != null ? fallback : trailing;
    }

    private static JsonNode parse(StringBuilder data, ObjectMapper mapper) {
        if (data.length() == 0) {
            return null;
        }
        try {
            return mapper.readTree(data.toString());
        } catch (Exception notJson) {
            return null; // a frame that is not JSON is not the answer
        }
    }

    private static String stripOneLeadingSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static List<String> lines(byte[] body) {
        return Arrays.asList(new String(body, StandardCharsets.UTF_8).split("\r\n|\n|\r", -1));
    }
}
