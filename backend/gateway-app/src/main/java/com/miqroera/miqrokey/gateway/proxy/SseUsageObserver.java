package com.miqroera.miqrokey.gateway.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
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
 *
 * <p>
 * Supports Anthropic Messages, OpenAI Responses, and OpenAI Chat Completions
 * SSE usage formats. The observer searches for a {@code "usage"} JSON object at
 * common nesting levels and extracts numeric token fields. The same extraction
 * ({@link #parseUsageJson}) serves non-streaming JSON responses, where usage
 * lives in the body instead of SSE events.
 * </p>
 */
public final class SseUsageObserver {

    static final int DEFAULT_MAX_EVENT_BYTES = 256 * 1024;

    /** Maximum number of usage observations retained per request. */
    static final int DEFAULT_MAX_OBSERVATIONS = 10;

    private static final Logger log = LoggerFactory.getLogger(SseUsageObserver.class);

    private final ObjectMapper objectMapper;
    private final int maxEventBytes;
    private final int maxObservations;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream eventData = new ByteArrayOutputStream();
    private final List<UsageObservation> observations = new ArrayList<>();
    private boolean discardingLine;
    private boolean discardingEvent;
    private boolean observationLimitReached;

    public SseUsageObserver() {
        this(new ObjectMapper(), DEFAULT_MAX_EVENT_BYTES);
    }

    SseUsageObserver(ObjectMapper objectMapper, int maxEventBytes) {
        this(objectMapper, maxEventBytes, DEFAULT_MAX_OBSERVATIONS);
    }

    SseUsageObserver(ObjectMapper objectMapper, int maxEventBytes, int maxObservations) {
        this.objectMapper = objectMapper;
        this.maxEventBytes = maxEventBytes;
        this.maxObservations = maxObservations;
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

    /**
     * Extracts usage metadata from a completed SSE event JSON object.
     *
     * <p>
     * Tries the following nesting patterns in order:
     * <ol>
     * <li>Root-level {@code "usage"} (common to all protocols)</li>
     * <li>{@code "message"."usage"} (Anthropic {@code message_start} events)</li>
     * <li>{@code "response"."usage"} (OpenAI Responses {@code response.completed}
     * events)</li>
     * </ol>
     * </p>
     *
     * <p>
     * Reasoning tokens are extracted from {@code output_tokens_details} (OpenAI
     * Responses) or {@code completion_tokens_details} (OpenAI Chat).
     * </p>
     */
    private void extractUsage(byte[] jsonBytes) {
        if (observationLimitReached) {
            return;
        }
        TokenBucket bucket = parseUsageJson(objectMapper, jsonBytes);
        if (bucket.isEmpty()) {
            return;
        }
        observations.add(new UsageObservation(bucket.inputTokens(), bucket.outputTokens(),
                bucket.cacheCreationInputTokens(), bucket.cacheReadInputTokens(), bucket.promptTokens(),
                bucket.completionTokens(), bucket.totalTokens(), bucket.reasoningTokens()));
        log.debug("SSE usage metadata observed");

        if (observations.size() >= maxObservations) {
            observationLimitReached = true;
            log.debug("SSE usage observer reached the maximum observation limit ({})", maxObservations);
        }
    }

    /**
     * Extracts usage metadata from a JSON body (an SSE event or a non-streaming
     * response).
     *
     * <p>
     * Tries the following nesting patterns in order:
     * <ol>
     * <li>Root-level {@code "usage"} (common to all protocols)</li>
     * <li>{@code "message"."usage"} (Anthropic {@code message_start} events and
     * Messages responses)</li>
     * <li>{@code "response"."usage"} (OpenAI Responses {@code response.completed}
     * events and Responses responses)</li>
     * </ol>
     * </p>
     *
     * <p>
     * Reasoning tokens are extracted from {@code output_tokens_details} (OpenAI
     * Responses) or {@code completion_tokens_details} (OpenAI Chat). Cache counters
     * fall back through the OpenAI standard nested paths
     * ({@code input_tokens_details.cached_tokens} /
     * {@code prompt_tokens_details.cached_tokens} / the write counterparts) and
     * DeepSeek's flat {@code prompt_cache_hit_tokens} when the Anthropic-standard
     * names are absent (#767). OpenAI-family counters include the cached tokens in
     * the primary prompt count, so the parse normalises input to exclude them —
     * every token is billed by exactly one rate bucket. Returns
     * {@link TokenBucket#EMPTY} when no usage object is present or the body is not
     * parseable.
     * </p>
     */
    public static TokenBucket parseUsageJson(ObjectMapper objectMapper, byte[] jsonBytes) {
        try {
            JsonNode root = objectMapper.readTree(jsonBytes);

            JsonNode usage = root.get("usage");
            if (usage == null && root.has("message")) {
                usage = root.path("message").get("usage");
            }
            if (usage == null && root.has("response")) {
                usage = root.path("response").get("usage");
            }
            if (usage == null || !usage.isObject()) {
                return TokenBucket.EMPTY;
            }

            Long reasoningTokens = null;
            if (usage.has("output_tokens_details")) {
                reasoningTokens = longValue(usage.path("output_tokens_details"), "reasoning_tokens");
            }
            if (reasoningTokens == null && usage.has("completion_tokens_details")) {
                reasoningTokens = longValue(usage.path("completion_tokens_details"), "reasoning_tokens");
            }

            // Cache reads, in precedence order (#767): the Anthropic-standard
            // name, the OpenAI *standard nested* paths (Responses'
            // input_tokens_details / Chat's prompt_tokens_details — cc-switch's
            // fallback chain), then DeepSeek's flat prompt_cache_hit_tokens.
            Long cacheRead = longValue(usage, "cache_read_input_tokens");
            boolean openAiInclusiveCounters = false;
            if (cacheRead == null) {
                cacheRead = nestedLong(usage, "input_tokens_details", "cached_tokens");
                if (cacheRead == null) {
                    cacheRead = nestedLong(usage, "prompt_tokens_details", "cached_tokens");
                }
                if (cacheRead == null) {
                    cacheRead = longValue(usage, "prompt_cache_hit_tokens");
                }
                openAiInclusiveCounters = cacheRead != null;
            }
            // Cache writes: Anthropic name, then the OpenAI nested write counters.
            // prompt_cache_miss_tokens is NOT a write: DeepSeek bills misses at
            // the plain input rate, so it must stay inside the input count
            // (mapping it to cache-creation priced it at the wrong rate — #767).
            Long cacheCreation = longValue(usage, "cache_creation_input_tokens");
            if (cacheCreation == null) {
                cacheCreation = nestedLong(usage, "input_tokens_details", "cache_write_tokens");
            }
            if (cacheCreation == null) {
                cacheCreation = nestedLong(usage, "prompt_tokens_details", "cache_write_tokens");
            }

            Long inputTokens = longValue(usage, "input_tokens");
            Long promptTokens = longValue(usage, "prompt_tokens");
            if (openAiInclusiveCounters) {
                // OpenAI-family counters INCLUDE the cached tokens in the primary
                // prompt count, while the Anthropic-standard form excludes them.
                // Normalise to "input excludes cache" so every token is billed by
                // exactly one rate bucket (input × inputPrice + read × readPrice)
                // instead of counting the cached part twice (#767).
                if (inputTokens != null) {
                    inputTokens = Math.max(0, inputTokens - cacheRead);
                } else if (promptTokens != null) {
                    promptTokens = Math.max(0, promptTokens - cacheRead);
                }
            }

            return new TokenBucket(inputTokens, longValue(usage, "output_tokens"), cacheCreation, cacheRead,
                    promptTokens, longValue(usage, "completion_tokens"), longValue(usage, "total_tokens"),
                    reasoningTokens);
        } catch (Exception ignored) {
            // Observation must never affect or expose the proxied response.
            log.debug("Usage metadata could not be parsed");
            return TokenBucket.EMPTY;
        }
    }

    private static Long longValue(JsonNode usage, String fieldName) {
        JsonNode value = usage.get(fieldName);
        return value != null && value.canConvertToLong() ? value.longValue() : null;
    }

    /** A numeric field inside a nested usage object ({@code *_tokens_details}). */
    private static Long nestedLong(JsonNode usage, String container, String field) {
        JsonNode inner = usage.get(container);
        return inner != null && inner.isObject() ? longValue(inner, field) : null;
    }

    /**
     * Returns token counters only. Event JSON and model content are never retained.
     */
    public List<UsageObservation> getObservations() {
        return List.copyOf(observations);
    }

    /**
     * Observed usage metadata from a single SSE event.
     *
     * <p>
     * Fields that are not present in the event are {@code null}. Different
     * protocols populate different subsets of fields:
     * <ul>
     * <li>Anthropic: {@code input_tokens}, {@code output_tokens},
     * {@code cache_creation_input_tokens}, {@code cache_read_input_tokens}</li>
     * <li>OpenAI Responses: {@code input_tokens}, {@code output_tokens},
     * {@code reasoning_tokens} (via {@code output_tokens_details})</li>
     * <li>OpenAI Chat: {@code prompt_tokens}, {@code completion_tokens},
     * {@code total_tokens}, {@code reasoning_tokens} (via
     * {@code completion_tokens_details})</li>
     * </ul>
     * </p>
     */
    public record UsageObservation(Long inputTokens, Long outputTokens, Long cacheCreationInputTokens,
            Long cacheReadInputTokens, Long promptTokens, Long completionTokens, Long totalTokens,
            Long reasoningTokens) {
    }
}
