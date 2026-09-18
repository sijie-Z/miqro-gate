package com.miqroera.miqrokey.adapters.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.spi.UsageObservation;
import com.miqroera.miqrokey.spi.UsageSource;

import java.util.Optional;

/**
 * Dual-shape provider usage parser shared by mainland-China OpenAI/Anthropic
 * compatible adapters (G3.2). The two wire shapes follow the ecosystem
 * conventions both vendors expose:
 *
 * <ul>
 * <li>OpenAI-compatible (root {@code usage}): {@code prompt_tokens} /
 * {@code completion_tokens}, plus the cache fields — standard
 * {@code input_tokens_details.cached_tokens} (Responses) /
 * {@code prompt_tokens_details.cached_tokens} (Chat & Zhipu GLM, G3.3) /
 * {@code prompt_cache_hit_tokens} (DeepSeek flat, G3.2) for reads, and the
 * {@code *_tokens_details.cache_write_tokens} counterparts for writes.</li>
 * <li>Anthropic Messages (root or {@code message.usage}): {@code input_tokens}
 * / {@code output_tokens} / {@code cache_creation_input_tokens} /
 * {@code cache_read_input_tokens}.</li>
 * </ul>
 *
 * <p>
 * {@link #parse} is a pure function: unknown and missing fields are tolerated,
 * parse failures yield an empty {@link Optional} and never affect the proxied
 * request.
 * </p>
 *
 * <p>
 * <b>Cache mapping (#767):</b> hit → {@code cacheRead}; {@code miss} is NOT a
 * write — DeepSeek bills misses at the plain input rate, so they stay inside
 * the input count (mapping them to {@code cacheCreation} priced them by the
 * wrong rate). OpenAI-family counters include the cached tokens in the primary
 * prompt count, so the parse normalises input to exclude them: every token is
 * billed by exactly one rate bucket. Only {@code cache_write_tokens} /
 * {@code cache_creation_input_tokens} map to {@code cacheCreation}.
 * </p>
 */
public final class TokenUsageParser {

    private TokenUsageParser() {
    }

    /**
     * Parses a usage JSON object into an observation. Accepts either entry shape;
     * unknown fields are ignored. Returns empty when the node is not an object with
     * at least one recognized token field.
     *
     * @param usage
     *            the provider usage node (OpenAI-compatible or Anthropic shape)
     */
    public static Optional<UsageObservation> parse(JsonNode usage) {
        return parse(usage, null);
    }

    /**
     * Parses a usage JSON object into an observation, using an externally provided
     * model id when the usage node itself does not contain {@code model}. This is
     * the common case for OpenAI-compatible responses, where {@code model} lives at
     * the response root as a sibling of {@code usage}.
     */
    public static Optional<UsageObservation> parse(JsonNode usage, String responseModelId) {
        if (usage == null || !usage.isObject()) {
            return Optional.empty();
        }
        Long input = longValue(usage, "input_tokens");
        Long output = longValue(usage, "output_tokens");
        Long cacheRead = longValue(usage, "cache_read_input_tokens");
        Long cacheCreation = longValue(usage, "cache_creation_input_tokens");
        Long prompt = longValue(usage, "prompt_tokens");
        Long completion = longValue(usage, "completion_tokens");
        // OpenAI-family cache reads (#767), Anthropic-standard names first:
        // Responses' input_tokens_details, Chat/GLM's prompt_tokens_details, then
        // DeepSeek's flat prompt_cache_hit_tokens.
        boolean openAiInclusiveCounters = false;
        if (cacheRead == null) {
            JsonNode inputDetails = usage.path("input_tokens_details");
            cacheRead = longValue(inputDetails, "cached_tokens");
            if (cacheRead == null) {
                JsonNode promptDetails = usage.path("prompt_tokens_details");
                cacheRead = longValue(promptDetails, "cached_tokens");
            }
            if (cacheRead == null) {
                cacheRead = longValue(usage, "prompt_cache_hit_tokens");
            }
            openAiInclusiveCounters = cacheRead != null;
        }
        // Cache writes: the nested *_tokens_details.cache_write_tokens counterparts.
        // prompt_cache_miss_tokens is deliberately NOT mapped — a miss is billed at
        // the plain input rate and stays inside the input count (#767).
        if (cacheCreation == null) {
            JsonNode inputDetails = usage.path("input_tokens_details");
            cacheCreation = longValue(inputDetails, "cache_write_tokens");
        }
        if (cacheCreation == null) {
            JsonNode promptDetails = usage.path("prompt_tokens_details");
            cacheCreation = longValue(promptDetails, "cache_write_tokens");
        }
        if (openAiInclusiveCounters) {
            // OpenAI-family counts include the cached tokens in the primary prompt
            // count; normalise to "input excludes cache" so `input × inputPrice +
            // cacheRead × readPrice` bills each token exactly once (#767).
            if (prompt != null) {
                prompt = Math.max(0, prompt - cacheRead);
            } else if (input != null) {
                input = Math.max(0, input - cacheRead);
            }
        }
        if (input == null && output == null && prompt == null && completion == null && cacheRead == null
                && cacheCreation == null) {
            return Optional.empty();
        }
        String modelId = usage.path("model").asText(null);
        if (modelId == null || modelId.isBlank()) {
            modelId = responseModelId;
        }
        return Optional.of(new UsageObservation(modelId != null && !modelId.isBlank() ? modelId : "unknown",
                prompt != null ? prompt : input, completion != null ? completion : output, cacheRead, cacheCreation,
                null, null, null, UsageSource.PROVIDER_RESPONSE, 1.0));
    }

    /** Parses a provider response body (OpenAI or Anthropic entry shape). */
    public static Optional<UsageObservation> parseResponse(ObjectMapper objectMapper, byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() && root.has("message")) {
                usage = root.path("message").path("usage");
            }
            String modelId = root.path("model").asText(null);
            if (modelId == null || modelId.isBlank()) {
                modelId = root.path("message").path("model").asText(null);
            }
            return parse(usage.isMissingNode() ? null : usage, modelId);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Long longValue(JsonNode usage, String fieldName) {
        JsonNode value = usage.get(fieldName);
        return value != null && value.canConvertToLong() ? value.longValue() : null;
    }
}
