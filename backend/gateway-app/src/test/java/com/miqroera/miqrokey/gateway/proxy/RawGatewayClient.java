package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.GatewayTestKeys;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A raw HTTP/1.1 client for the gateway's own error responses.
 *
 * <p>
 * {@code WebTestClient} decodes the body, and decoding hides how the head
 * frames it: a response that announces more bytes than it sends is a perfectly
 * well-formed exchange to a decoding client, while a real one (curl, any SDK)
 * waits for the bytes the head promised. Tests that care about the contract the
 * gateway writes on the wire — rather than the body alone — therefore read the
 * head and the body as bytes.
 * </p>
 */
final class RawGatewayClient {

    private RawGatewayClient() {
    }

    /**
     * Posts {@link AnthropicFixtures#REQUEST_NON_STREAMING} to {@code /v1/messages}
     * and reads the response head, then as much body as the gateway sends within
     * {@code readTimeoutMillis}.
     */
    static RawResponse callNonStreaming(int port, int readTimeoutMillis, boolean keepAlive) throws IOException {
        byte[] body = AnthropicFixtures.REQUEST_NON_STREAMING.getBytes(StandardCharsets.UTF_8);
        // Never printed: the presented key is secret material (see GatewayTestKeys).
        String request = "POST /v1/messages HTTP/1.1\r\n" + "Host: 127.0.0.1:" + port + "\r\n"
                + "Authorization: Bearer " + GatewayTestKeys.DEFAULT_KEY.presented() + "\r\n"
                + "Content-Type: application/json\r\n" + "Content-Length: " + body.length + "\r\n"
                + (keepAlive ? "" : "Connection: close\r\n") + "\r\n";

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(readTimeoutMillis);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b == -1) {
                    break;
                }
                headBytes.write(b);
                state = (state % 2 == 0 && b == '\r') || (state % 2 == 1 && b == '\n') ? state + 1 : 0;
            }

            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            String termination = "server-closed";
            try {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bodyBytes.write(buffer, 0, read);
                }
            } catch (SocketTimeoutException e) {
                termination = "no-server-close-within-" + readTimeoutMillis + "ms";
            }

            String head = headBytes.toString(StandardCharsets.UTF_8);
            return new RawResponse(head, headers(head), bodyBytes.toString(StandardCharsets.UTF_8), termination);
        }
    }

    private static Map<String, String> headers(String head) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    record RawResponse(String head, Map<String, String> headers, String body, String termination) {

        String statusLine() {
            int end = head.indexOf("\r\n");
            return end < 0 ? head : head.substring(0, end);
        }

        int bodyBytes() {
            return body.getBytes(StandardCharsets.UTF_8).length;
        }

        Integer declaredContentLength() {
            String value = headers.get("content-length");
            return value == null ? null : Integer.valueOf(value);
        }

        String describe() {
            return "status-line=" + statusLine() + " head=[" + head.replace("\r\n", " | ") + "]"
                    + " declared-content-length=" + declaredContentLength() + " body-bytes-received=" + bodyBytes()
                    + " body=[" + body + "] ended-by=" + termination;
        }
    }
}
