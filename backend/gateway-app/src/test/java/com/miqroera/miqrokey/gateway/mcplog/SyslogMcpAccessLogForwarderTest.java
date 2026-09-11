package com.miqroera.miqrokey.gateway.mcplog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import com.miqroera.miqrokey.domain.model.McpAccessStatus;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Syslog sink of the I19 access-log forwarding: RFC 5424 frames
 * ({@code <PRI>1 <ts> <host> miqrokey-gateway - - <json>}) over UDP (default)
 * and TCP, facility-driven PRI, and never-throw delivery behavior.
 */
@DisplayName("MCP access log syslog forwarder")
class SyslogMcpAccessLogForwarderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpAccessLogEntry entry(String requestId, String tool) {
        return new McpAccessLogEntry(UUID.randomUUID(), GatewayTestKeys.TENANT_ID, UUID.randomUUID(), "weather-mcp",
                UUID.randomUUID(), "drill", "tools/call", tool, McpAccessStatus.FORWARDED, 200, requestId,
                Instant.parse("2026-09-11T08:00:00Z"), "sess-1", null);
    }

    private static JsonNode jsonOf(String frame) throws Exception {
        int marker = frame.indexOf("- - ");
        assertThat(marker).as("RFC 5424 structured-data terminator present").isGreaterThan(0);
        return MAPPER.readTree(frame.substring(marker + 4).trim());
    }

    @Test
    @DisplayName("UDP delivers one RFC 5424 frame per entry with LOCAL0 PRI and JSON metadata")
    void udpFrames() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            SyslogMcpAccessLogForwarder forwarder = new SyslogMcpAccessLogForwarder("127.0.0.1",
                    receiver.getLocalPort(), "UDP", "LOCAL0", MAPPER);
            forwarder.forward(List.of(entry("req-1", "forecast"), entry("req-2", "alerts")));

            for (int i = 0; i < 2; i++) {
                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiver.setSoTimeout(3000);
                receiver.receive(packet);
                String frame = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                assertThat(frame).startsWith("<134>1 2026-09-11T08:00:00Z ").contains(" miqrokey-gateway - - ");
                JsonNode json = jsonOf(frame);
                assertThat(json.get("aigw.mcp.rpc_method").asText()).isEqualTo("tools/call");
                assertThat(json.get("aigw.mcp.request_id").asText()).startsWith("req-");
            }
            assertThat(forwarder.failureCount()).isZero();
        }
    }

    @Test
    @DisplayName("TCP streams frames with a facility-driven PRI")
    void tcpFrames() throws Exception {
        AtomicReference<String> frame = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    socket.getInputStream().transferTo(out);
                    frame.set(out.toString(StandardCharsets.UTF_8));
                    received.countDown();
                } catch (Exception ignored) {
                    // assertions below surface the timeout
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            SyslogMcpAccessLogForwarder forwarder = new SyslogMcpAccessLogForwarder("127.0.0.1", server.getLocalPort(),
                    "TCP", "DAEMON", MAPPER);
            forwarder.forward(List.of(entry("req-9", "forecast")));

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            String line = frame.get().trim();
            assertThat(line).startsWith("<30>1 "); // DAEMON(3)*8 + INFO(6)
            assertThat(jsonOf(line).get("aigw.mcp.tool").asText()).isEqualTo("forecast");
        }
    }

    @Test
    @DisplayName("unreachable receivers and invalid configuration never throw")
    void failuresAndGuards() {
        SyslogMcpAccessLogForwarder unreachable = new SyslogMcpAccessLogForwarder("127.0.0.1", 1, "TCP", "LOCAL0",
                MAPPER);
        unreachable.forward(List.of(entry("req-1", "forecast")));
        assertThat(unreachable.failureCount()).isEqualTo(1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SyslogMcpAccessLogForwarder(" ", 514, "UDP", "LOCAL0", MAPPER));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SyslogMcpAccessLogForwarder("127.0.0.1", 0, "UDP", "LOCAL0", MAPPER));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SyslogMcpAccessLogForwarder("127.0.0.1", 514, "SCTP", "LOCAL0", MAPPER));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SyslogMcpAccessLogForwarder("127.0.0.1", 514, "UDP", "BOGUS", MAPPER));
    }
}
