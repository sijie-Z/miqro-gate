package com.miqroera.miqrokey.gateway.retention;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.RetentionConfig;
import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import com.miqroera.miqrokey.gateway.retention.RetentionTextExtractor.Protocol;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway side-channel of the compliance retention pipeline (ADR-0014 v3,
 * default fully OFF). On an authenticated LLM request whose tenant retention
 * config is enabled, the buffered request body is inspected for user-role text
 * (P1: USER_TEXT_ONLY, no other content), that text is encrypted into a
 * {@link RetentionEnvelope} and handed to the bounded queue; the flush task
 * publishes to the configured {@link RetentionPublisher}.
 *
 * <p>
 * Everything is best-effort and never touches the hot path outcome: extraction
 * or crypto failures drop the event with a counted WARN, the queue saturates
 * with drop + count, and the publisher runs off the event loop. When no crypto
 * provider is available (crypto disabled) or no publisher is configured,
 * capture is a silent no-op (fail closed).
 * </p>
 *
 * <p>
 * AAD note: the envelope cipher is bound to (tenant, a synthetic constant
 * "credential") because the shared AES provider keys its AAD on credential ids
 * — the constant is documented here, never matches a real credential, and keeps
 * the envelope decryptable by the authorized reader with the same key version.
 * </p>
 */
public final class RetentionSidecar {

    private static final Logger log = LoggerFactory.getLogger(RetentionSidecar.class);
    private static final UUID RETENTION_AAD_ID = UUID
            .nameUUIDFromBytes("miqro-retention-envelope".getBytes(StandardCharsets.UTF_8));
    private static final long DROP_LOG_THROTTLE = 100;

    private final RouteSnapshotProvider routeSnapshotProvider;
    private final java.util.function.Supplier<KeyEncryptionProvider> cryptoProvider;
    private final RetentionPublisher publisher;
    private final RetentionTextExtractor extractor = new RetentionTextExtractor();
    private final int capacity;
    private final long flushIntervalMs;
    private final int maxTextChars;
    private final Clock clock;
    private final ArrayBlockingQueue<RetentionEnvelope> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong truncatedCount = new AtomicLong();
    private volatile ScheduledExecutorService scheduler;

    public RetentionSidecar(RouteSnapshotProvider routeSnapshotProvider,
            org.springframework.beans.factory.ObjectProvider<KeyEncryptionProvider> cryptoProvider,
            RetentionPublisher publisher, int capacity, long flushIntervalMs, int maxTextChars, Clock clock) {
        if (capacity <= 0 || flushIntervalMs <= 0 || maxTextChars <= 0) {
            throw new IllegalArgumentException("retention capacity/flush-interval-ms/max-text-chars must be positive");
        }
        this.routeSnapshotProvider = routeSnapshotProvider;
        this.cryptoProvider = cryptoProvider::getIfAvailable;
        this.publisher = publisher;
        this.capacity = capacity;
        this.flushIntervalMs = flushIntervalMs;
        this.maxTextChars = maxTextChars;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Best-effort capture for one authenticated LLM request; never throws. */
    public void capture(String path, byte[] body, AuthContext ctx, String gatewayRequestId) {
        try {
            UUID tenantId = ctx.tenantId();
            RetentionConfig config = routeSnapshotProvider.current().retention(tenantId);
            if (config == null || !config.enabled()) {
                return;
            }
            Protocol protocol = protocolOf(path);
            if (protocol == null) {
                return;
            }
            String text = extractor.extract(protocol, body);
            if (text.isEmpty()) {
                return;
            }
            if (text.length() > maxTextChars) {
                countDrop("text too long");
                return;
            }
            KeyEncryptionProvider provider = cryptoProvider.get();
            if (provider == null) {
                // Crypto disabled: fail closed — never ship plaintext.
                return;
            }
            byte[] plain = text.getBytes(StandardCharsets.UTF_8);
            // Tenant content cap (#367, I18): oversized payloads are truncated on a
            // UTF-8 boundary and flagged/counted instead of dropped.
            boolean truncated = false;
            int cap = config.maxContentBytes();
            if (plain.length > cap) {
                plain = truncateUtf8(plain, cap);
                truncated = true;
                truncatedCount.incrementAndGet();
                if (truncatedCount.get() % DROP_LOG_THROTTLE == 0) {
                    log.warn("retention content truncated {} times (latest tenant {})", truncatedCount.get(), tenantId);
                }
            }
            EncryptedSecret secret = provider.encrypt(plain, tenantId, RETENTION_AAD_ID);
            RetentionEnvelope envelope = new RetentionEnvelope(UUID.randomUUID(), tenantId, ctx.key().userId(),
                    ctx.key().keyId(), protocol.name(), gatewayRequestId, Instant.now(clock), secret.keyVersion(),
                    secret.ciphertext(), secret.nonce(), plain.length, truncated);
            if (!offer(envelope)) {
                countDrop("queue saturated");
            }
            ensureScheduler();
        } catch (Exception e) {
            countDrop(e.getClass().getSimpleName());
        }
    }

    /** Number of events dropped since start (observability/tests). */
    public long droppedCount() {
        return dropped.get();
    }

    /** Number of captures truncated at the tenant content cap (#367, I18). */
    public long truncatedCount() {
        return truncatedCount.get();
    }

    /** First {@code cap} bytes, backed off to a UTF-8 character boundary. */
    private static byte[] truncateUtf8(byte[] plain, int cap) {
        int end = cap;
        while (end > 0 && (plain[end] & 0xC0) == 0x80) {
            end--;
        }
        return java.util.Arrays.copyOf(plain, end);
    }

    /** Drains the queue into the publisher synchronously (tests). */
    void flushNow() {
        flush();
    }

    private boolean offer(RetentionEnvelope envelope) {
        return queue.offer(envelope);
    }

    private synchronized void ensureScheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "retention-flush");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void flush() {
        List<RetentionEnvelope> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        for (RetentionEnvelope envelope : batch) {
            try {
                publisher.publish(envelope);
            } catch (Exception e) {
                // Requeue once for retry; idempotent consumers tolerate repeats.
                if (!queue.offer(envelope)) {
                    dropped.incrementAndGet();
                }
                log.warn("retention publish failed for envelope {}", envelope.eventId(), e);
            }
        }
    }

    private void countDrop(String reason) {
        long count = dropped.incrementAndGet();
        if (count % DROP_LOG_THROTTLE == 1) {
            log.warn("retention event dropped ({}) - total {}", reason, count);
        }
    }

    private static Protocol protocolOf(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/v1/messages")) {
            return Protocol.ANTHROPIC_MESSAGES;
        }
        if (path.startsWith("/v1/chat/completions")) {
            return Protocol.OPENAI_CHAT;
        }
        if (path.startsWith("/v1/responses")) {
            return Protocol.OPENAI_RESPONSES;
        }
        return null;
    }

    @PreDestroy
    public void close() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
