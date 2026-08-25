package com.miqroera.miqrokey.gateway.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.Map;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records time-to-first-byte (TTFB) metadata without recording request or
 * response bodies.
 *
 * <p>
 * This class is request-scoped. Create a new instance for each proxied request,
 * wrap the response body flux with {@link #wrap(Flux)}, and call
 * {@link #recordCompletion(SignalType)} when the stream completes.
 * </p>
 *
 * <p>
 * All timestamps are epoch milliseconds. Metadata is stored in a
 * {@link ConcurrentHashMap} that can be consumed by metrics or logging.
 * </p>
 */
public final class TtfbRecorder {

    private static final Logger log = LoggerFactory.getLogger(TtfbRecorder.class);

    private final String requestId;
    private final long requestStartMillis;
    private final Clock clock;
    private final AtomicLong firstByteMillis = new AtomicLong(-1);
    private final AtomicBoolean firstByteRecorded = new AtomicBoolean(false);
    private final AtomicLong completionMillis = new AtomicLong(-1);
    private final AtomicReference<SignalType> terminalSignal = new AtomicReference<>();

    /**
     * Creates a new TTFB recorder for a proxied request.
     *
     * @param requestId
     *            a unique identifier for the request (for correlation)
     * @param startMillis
     *            the timestamp when the upstream request was initiated
     */
    public TtfbRecorder(String requestId, long startMillis, Clock clock) {
        this.requestId = requestId;
        this.requestStartMillis = startMillis;
        this.clock = clock;
    }

    /**
     * Wraps a response body {@link Flux} with TTFB observation. The first emitted
     * {@link DataBuffer} triggers the TTFB recording. All buffers pass through
     * unchanged.
     */
    public Flux<DataBuffer> wrap(Flux<DataBuffer> body) {
        return body.doOnNext(buffer -> {
            if (firstByteRecorded.compareAndSet(false, true)) {
                long now = clock.millis();
                firstByteMillis.set(now);
                long ttfb = now - requestStartMillis;
                log.debug("TTFB recorded: requestId={}, ttfbMs={}, readableBytes={}", requestId, ttfb,
                        buffer.readableByteCount());
            }
        });
    }

    /**
     * Records the completion of the upstream response stream.
     *
     * @param signalType
     *            the Reactor signal type (ON_COMPLETE, ON_ERROR, CANCEL)
     */
    public void recordCompletion(SignalType signalType) {
        terminalSignal.set(signalType);
        completionMillis.set(clock.millis());
        long ttfb = firstByteMillis.get() > 0 ? firstByteMillis.get() - requestStartMillis : -1;
        long total = completionMillis.get() - requestStartMillis;
        log.debug("Request completed: requestId={}, signalType={}, ttfbMs={}, totalMs={}", requestId, signalType, ttfb,
                total);
    }

    /**
     * Returns the terminal signal observed on the upstream response stream
     * (ON_COMPLETE, ON_ERROR or CANCEL), or null if the stream never terminated.
     * This is the authoritative signal for the client-cancel case: cancelling the
     * observed stream is what closes the upstream connection.
     */
    public SignalType terminalSignal() {
        return terminalSignal.get();
    }

    /**
     * Returns the recorded metadata as an immutable map. Never contains request or
     * response body content.
     */
    public Map<String, Object> getMetadata() {
        return Map.of("requestId", requestId, "requestStartEpochMillis", requestStartMillis, "firstByteEpochMillis",
                firstByteMillis.get(), "completionEpochMillis", completionMillis.get());
    }

    /**
     * Returns the request start timestamp (epoch millis).
     */
    public long requestStartMillis() {
        return requestStartMillis;
    }

    /**
     * Returns the TTFB in milliseconds, or -1 if not yet recorded.
     */
    public long ttfbMillis() {
        long fb = firstByteMillis.get();
        return fb > 0 ? fb - requestStartMillis : -1;
    }

    /**
     * Returns the total request duration in milliseconds, or -1 if not yet
     * completed.
     */
    public long totalMillis() {
        long c = completionMillis.get();
        return c > 0 ? c - requestStartMillis : -1;
    }

    /**
     * Returns the wall-clock epoch millis of the first observed upstream byte, or
     * -1 if the stream never emitted.
     */
    public long firstByteEpochMillis() {
        return firstByteMillis.get();
    }

    // package-private helpers for testing

    String requestId() {
        return requestId;
    }

    long firstByteMillisRaw() {
        return firstByteMillis.get();
    }
}
