package com.miqroera.miqrokey.gateway.mcplog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.model.McpAccessLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Webhook sink (I19): POSTs the flushed batch as a JSON array of
 * {@code aigw.mcp.*} objects (doc 16 field set) to the configured URL, with an
 * optional {@code Authorization: Bearer <token>}. Runs on the flush scheduler
 * thread; bounded by a request timeout and never throws — a failing endpoint
 * degrades to a throttled WARN (no retry storm; the next batch is fresh).
 */
public final class WebhookMcpAccessLogForwarder implements McpAccessLogForwarder {

    private static final Logger log = LoggerFactory.getLogger(WebhookMcpAccessLogForwarder.class);
    private static final long FAILURE_LOG_THROTTLE = 100;

    private final URI url;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong failures = new AtomicLong();

    public WebhookMcpAccessLogForwarder(String url, String token, long timeoutMs, ObjectMapper objectMapper) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("webhook url is required");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("webhook timeout must be > 0");
        }
        this.url = URI.create(url.trim());
        this.token = token;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5000))).build();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void forward(List<McpAccessLogEntry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(batch.stream().map(McpAccessLogJson::of).toList());
            HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                countFailure("non-2xx status " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            countFailure(e.getMessage());
        }
    }

    /** Number of failed deliveries (observability + tests). */
    public long failureCount() {
        return failures.get();
    }

    private void countFailure(String reason) {
        long count = failures.incrementAndGet();
        if (count % FAILURE_LOG_THROTTLE == 1) {
            log.warn("MCP access log webhook delivery failed {} times (latest: {})", count, reason);
        }
    }
}
