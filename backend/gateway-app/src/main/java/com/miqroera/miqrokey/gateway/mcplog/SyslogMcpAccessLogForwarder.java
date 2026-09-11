package com.miqroera.miqrokey.gateway.mcplog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Syslog sink (I19, Tencent raw 16 log shipping): one RFC 5424 message per
 * entry — {@code <PRI>1 <ts> <host> miqrokey-gateway - - <json>} — with the
 * {@code aigw.mcp.*} JSON object as the MSG, over UDP (default) or TCP.
 * Facility defaults to LOCAL0 (PRI = facility*8 + severity.INFO). Runs on the
 * flush scheduler thread; never throws — delivery failures degrade to a
 * throttled WARN.
 */
public final class SyslogMcpAccessLogForwarder implements McpAccessLogForwarder {

    private static final Logger log = LoggerFactory.getLogger(SyslogMcpAccessLogForwarder.class);
    private static final long FAILURE_LOG_THROTTLE = 100;

    /** Syslog facility codes (RFC 5424 §6.2.1). */
    private static final Map<String, Integer> FACILITIES = Map.ofEntries(Map.entry("KERN", 0), Map.entry("USER", 1),
            Map.entry("MAIL", 2), Map.entry("DAEMON", 3), Map.entry("AUTH", 4), Map.entry("SYSLOG", 5),
            Map.entry("LPR", 6), Map.entry("NEWS", 7), Map.entry("UUCP", 8), Map.entry("CRON", 9),
            Map.entry("AUTHPRIV", 10), Map.entry("FTP", 11), Map.entry("LOCAL0", 16), Map.entry("LOCAL1", 17),
            Map.entry("LOCAL2", 18), Map.entry("LOCAL3", 19), Map.entry("LOCAL4", 20), Map.entry("LOCAL5", 21),
            Map.entry("LOCAL6", 22), Map.entry("LOCAL7", 23));

    private static final int SEVERITY_INFO = 6;

    private final String host;
    private final int port;
    private final boolean tcp;
    private final int priority;
    private final String hostname;
    private final ObjectMapper objectMapper;
    private final AtomicLong failures = new AtomicLong();

    public SyslogMcpAccessLogForwarder(String host, int port, String protocol, String facility,
            ObjectMapper objectMapper) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("syslog host is required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("syslog port must be 1..65535");
        }
        String normalizedProtocol = protocol == null ? "UDP" : protocol.trim().toUpperCase(Locale.ROOT);
        if (!("UDP".equals(normalizedProtocol) || "TCP".equals(normalizedProtocol))) {
            throw new IllegalArgumentException("syslog protocol must be UDP or TCP");
        }
        String normalizedFacility = facility == null ? "LOCAL0" : facility.trim().toUpperCase(Locale.ROOT);
        Integer facilityCode = FACILITIES.get(normalizedFacility);
        if (facilityCode == null) {
            throw new IllegalArgumentException("unknown syslog facility: " + facility);
        }
        this.host = host.trim();
        this.port = port;
        this.tcp = "TCP".equals(normalizedProtocol);
        this.priority = facilityCode * 8 + SEVERITY_INFO;
        this.objectMapper = objectMapper;
        this.hostname = resolveHostname();
    }

    @Override
    public String name() {
        return "syslog";
    }

    @Override
    public void forward(List<McpAccessLogEntry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            if (tcp) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 3000);
                    OutputStream out = socket.getOutputStream();
                    for (McpAccessLogEntry entry : batch) {
                        out.write(frame(entry));
                    }
                    out.flush();
                }
            } else {
                try (DatagramSocket socket = new DatagramSocket()) {
                    for (McpAccessLogEntry entry : batch) {
                        byte[] message = frame(entry);
                        socket.send(new DatagramPacket(message, message.length, InetAddress.getByName(host), port));
                    }
                }
            }
        } catch (Exception e) {
            long count = failures.incrementAndGet();
            if (count % FAILURE_LOG_THROTTLE == 1) {
                log.warn("MCP access log syslog delivery failed {} times (latest: {})", count, e.getMessage());
            }
        }
    }

    /** Number of failed deliveries (observability + tests). */
    public long failureCount() {
        return failures.get();
    }

    /** One RFC 5424 frame: {@code <PRI>1 TIMESTAMP HOST APP - - JSON}. */
    byte[] frame(McpAccessLogEntry entry) throws Exception {
        String json = objectMapper.writeValueAsString(McpAccessLogJson.of(entry));
        String timestamp = entry.occurredAt() == null ? "-" : entry.occurredAt().toString();
        String line = "<" + priority + ">1 " + timestamp + " " + hostname + " miqrokey-gateway - - " + json + "\n";
        return line.getBytes(StandardCharsets.UTF_8);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "-";
        }
    }
}
