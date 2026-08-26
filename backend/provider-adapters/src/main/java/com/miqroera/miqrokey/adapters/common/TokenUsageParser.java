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
 * {@code completion_tokens}, plus the cache fields
 * {@code prompt_cache_hit_tokens} (read) / {@code prompt_cache_miss_tokens}
 * (write) and the OpenAI-standard/Zhipu GLM
 * {@code prompt_tokens_details.cached_tokens} (read) (G3.3).</li>
 * <li>Anthropic Messages (root or {@code message.usage}): {@code input_tokens}
 * / {@code output_tokens} / {@code cache_creation_input_tokens} /
 * {@code cache_read_input_tokens}.</li>
 * </ul>
 *
 * <p>
 * {@link #parse} is a pure function: unknown and missing fields are tolerated,
 * parse failures yield an empty {@link Optional} and never affect the proxied
 * request. Cache mapping: hit → cacheRead, miss/creation → cacheCreation.
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
        // OpenAI-compatible cache fields.
        if (cacheRead == null) {
            cacheRead = longValue(usage, "prompt_cache_hit_tokens");
        }
        if (cacheCreation == null) {
            cacheCreation = longValue(usage, "prompt_cache_miss_tokens");
        }
        // OpenAI-standard and Zhipu GLM shape: cache read is reported as
        // prompt_tokens_details.cached_tokens (G3.3 official docs).
        if (cacheRead == null) {
            JsonNode details = usage.path("prompt_tokens_details");
            cacheRead = longValue(details, "cached_tokens");
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
