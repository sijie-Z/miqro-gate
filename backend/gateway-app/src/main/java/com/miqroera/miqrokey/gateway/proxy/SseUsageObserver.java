package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Observes bounded usage metadata from an SSE response without changing the
 * response buffers or retaining event bodies.
 */
public final class SseUsageObserver {

    static final int DEFAULT_MAX_EVENT_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(SseUsageObserver.class);

    private final ObjectMapper objectMapper;
    private final int maxEventBytes;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream eventData = new ByteArrayOutputStream();
    private final List<UsageObservation> observations = new ArrayList<>();
    private boolean discardingLine;
    private boolean discardingEvent;

    public SseUsageObserver() {
        this(new ObjectMapper(), DEFAULT_MAX_EVENT_BYTES);
    }

    SseUsageObserver(ObjectMapper objectMapper, int maxEventBytes) {
        this.objectMapper = objectMapper;
        this.maxEventBytes = maxEventBytes;
    }

    /** Passes every buffer through unchanged while observing a bounded copy. */
    public Flux<DataBuffer> wrap(Flux<DataBuffer> body) {
        return body.doOnNext(this::observe);
    }

    private void observe(DataBuffer buffer) {
        int originalReadPosition = buffer.readPosition();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        buffer.readPosition(originalReadPosition);

        for (byte value : bytes) {
            if (value == '\n') {
                completeLine();
            } else if (!discardingLine) {
                if (lineBuffer.size() >= maxEventBytes) {
                    lineBuffer.reset();
                    discardingLine = true;
                } else {
                    lineBuffer.write(value);
                }
            }
        }
    }

    private void completeLine() {
        if (discardingLine) {
            discardingLine = false;
            discardingEvent = true;
            log.debug("SSE usage observer discarded an oversized event");
            return;
        }

        byte[] line = lineBuffer.toByteArray();
        lineBuffer.reset();
        int length = line.length;
        if (length > 0 && line[length - 1] == '\r') {
            length--;
        }

        if (length == 0) {
            dispatchEvent();
            return;
        }
        if (discardingEvent || length < 5 || line[0] != 'd' || line[1] != 'a' || line[2] != 't' || line[3] != 'a'
                || line[4] != ':') {
            return;
        }

        int valueStart = length > 5 && line[5] == ' ' ? 6 : 5;
        int valueLength = length - valueStart;
        int separatorLength = eventData.size() == 0 ? 0 : 1;
        if (eventData.size() + separatorLength + valueLength > maxEventBytes) {
            eventData.reset();
            discardingEvent = true;
            log.debug("SSE usage observer discarded an oversized event");
            return;
        }
        if (separatorLength == 1) {
            eventData.write('\n');
        }
        eventData.write(line, valueStart, valueLength);
    }

    private void dispatchEvent() {
        if (!discardingEvent && eventData.size() > 0) {
            extractUsage(eventData.toByteArray());
        }
        eventData.reset();
        discardingEvent = false;
    }

    private void extractUsage(byte[] jsonBytes) {
        try {
            JsonNode root = objectMapper.readTree(jsonBytes);
            JsonNode usage = root.get("usage");
            if (usage == null && root.has("message")) {
                usage = root.path("message").get("usage");
            }
            if (usage == null || !usage.isObject()) {
                return;
            }
            observations.add(new UsageObservation(longValue(usage, "input_tokens"), longValue(usage, "output_tokens"),
                    longValue(usage, "cache_creation_input_tokens"), longValue(usage, "cache_read_input_tokens")));
            log.debug("SSE usage metadata observed");
        } catch (Exception ignored) {
            // Observation must never affect or expose the proxied response.
            log.debug("SSE usage metadata could not be parsed");
        }
    }

    private static Long longValue(JsonNode usage, String fieldName) {
        JsonNode value = usage.get(fieldName);
        return value != null && value.canConvertToLong() ? value.longValue() : null;
    }

    /**
     * Returns token counters only. Event JSON and model content are never retained.
     */
    public List<UsageObservation> getObservations() {
        return List.copyOf(observations);
    }

    public record UsageObservation(Long inputTokens, Long outputTokens, Long cacheCreationInputTokens,
            Long cacheReadInputTokens) {
    }
}
