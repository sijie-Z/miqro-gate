package com.miqroera.miqrokey.gateway.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bare-TCP upstream stub for wire-level failure modes that an HTTP server
 * framework cannot express: a response whose announced framing is never
 * satisfied because the socket is closed first.
 *
 * <p>
 * {@code AnthropicMockProvider} cannot produce these — reactor-netty's HTTP
 * server refuses to complete a response whose body is shorter than its declared
 * {@code Content-Length}, and its "disconnect" modes close before the status
 * line. This stub writes the exact bytes it is given and then closes the socket,
 * so the gateway sees precisely what a dying provider (or an intermediary proxy)
 * puts on the wire.
 * </p>
 *
 * <p>
 * The request is fully drained before the response is written: closing a socket
 * that still has unread bytes in its receive buffer makes the OS send an RST
 * instead of a FIN, which would be a different failure than the one under test.
 * </p>
 */
final class RawUpstreamStub implements AutoCloseable {

    private final ServerSocket server;
    private final AtomicInteger connections = new AtomicInteger();
    private final CopyOnWriteArrayList<String> requestHeads = new CopyOnWriteArrayList<>();
    private volatile String rawResponseHead = "";
    private volatile String rawResponseBody = "";
    private volatile long holdMillis = 250L;

    RawUpstreamStub() throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "ph31-raw-upstream-stub");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getLocalPort();
    }

    int port() {
        return server.getLocalPort();
    }

    int connections() {
        return connections.get();
    }

    /** Raw request heads received so far, for evidence in failure output. */
    java.util.List<String> requestHeads() {
        return java.util.List.copyOf(requestHeads);
    }

    /**
     * Installs the exact status line + headers written for every subsequent
     * connection. The body (if any) is written immediately after, then the socket
     * is held open for {@link #holdOpen} before closing — a provider that dies
     * mid-response does not put its last byte and the FIN in the same packet.
     */
    void respondWith(String responseHead, String responseBody) {
        this.rawResponseHead = responseHead;
        this.rawResponseBody = responseBody;
    }

    void holdOpen(long millis) {
        this.holdMillis = millis;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                connections.incrementAndGet();
                Thread worker = new Thread(() -> serve(socket), "ph31-raw-upstream-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                return; // socket closed: stub shutting down
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(2000);
            String head = readRequest(socket.getInputStream());
            requestHeads.add(head);
            String responseHead = rawResponseHead;
            if (responseHead.isEmpty()) {
                return;
            }
            OutputStream out = socket.getOutputStream();
            out.write(responseHead.getBytes(StandardCharsets.UTF_8));
            if (!rawResponseBody.isEmpty()) {
                out.write(rawResponseBody.getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
            if (holdMillis > 0) {
                Thread.sleep(holdMillis);
            }
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads the request head and, when {@code Content-Length} is declared, exactly
     * that many body bytes, so the socket is quiet when it is closed.
     */
    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while (matched < 4 && (b = in.read()) != -1) {
            head.write(b);
            matched = switch (matched) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : (b == '\r' ? 1 : 0);
                case 2 -> b == '\r' ? 3 : 0;
                default -> b == '\n' ? 4 : 0;
            };
        }
        String text = head.toString(StandardCharsets.UTF_8);
        long contentLength = text.lines()
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                .mapToLong(line -> Long.parseLong(line.substring(line.indexOf(':') + 1).trim())).findFirst().orElse(0L);
        long remaining = contentLength;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    break;
                }
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
        return text;
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
