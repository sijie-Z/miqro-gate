package com.miqroera.miqrokey.adapters.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObservation;
import com.miqroera.miqrokey.spi.UsageObserver;
import com.miqroera.miqrokey.spi.UsageSource;

import java.util.Optional;

/**
 * DeepSeek usage observer（G3.1）。SPI 契约：observer 由适配器在请求前创建， 调用方在解析上游响应后通过
 * {@link #onUsage} 恰好回调一次；观察绝不改写或 阻塞代理字节流。
 *
 * <p>
 * DeepSeek 两种入口的 usage 形状：
 * <ul>
 * <li>OpenAI 兼容（root {@code usage}）：{@code prompt_tokens} /
 * {@code completion_tokens}，以及 DeepSeek 特有的
 * {@code prompt_cache_hit_tokens}（命中缓存）/{@code prompt_cache_miss_tokens}
 * （未命中缓存）。</li>
 * <li>Anthropic Messages（root 或 {@code message.usage}）： {@code input_tokens} /
 * {@code output_tokens} / {@code cache_creation_input_tokens} /
 * {@code cache_read_input_tokens}。</li>
 * </ul>
 *
 * <p>
 * {@link #parse(JsonNode)} 是纯函数解析器，容忍未知字段与缺失字段；解析失败 返回空
 * {@link Optional}，绝不影响请求。cache 字段映射：命中 → cacheRead， 未命中/创建 → cacheCreation。
 * </p>
 */
public final class DeepSeekUsageObserver implements UsageObserver {

    private final UsageContext context;
    private UsageObservation last;

    public DeepSeekUsageObserver(UsageContext context) {
        this.context = context;
    }

    @Override
    public synchronized void onUsage(UsageObservation observation) {
        this.last = observation;
    }

    /** The most recently observed usage; empty when nothing was reported. */
    public synchronized Optional<UsageObservation> lastObservation() {
        return Optional.ofNullable(last);
    }

    public UsageContext context() {
        return context;
    }

    /**
     * Parses a DeepSeek usage JSON object into an observation. Accepts either entry
     * shape; unknown fields are ignored. Returns empty when the node is not an
     * object with at least one recognized token field.
     */
    public static Optional<UsageObservation> parse(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Optional.empty();
        }
        Long input = longValue(usage, "input_tokens");
        Long output = longValue(usage, "output_tokens");
        Long cacheRead = longValue(usage, "cache_read_input_tokens");
        Long cacheCreation = longValue(usage, "cache_creation_input_tokens");
        Long prompt = longValue(usage, "prompt_tokens");
        Long completion = longValue(usage, "completion_tokens");
        // DeepSeek-specific cache fields on the OpenAI-compatible entry.
        if (cacheRead == null) {
            cacheRead = longValue(usage, "prompt_cache_hit_tokens");
        }
        if (cacheCreation == null) {
            cacheCreation = longValue(usage, "prompt_cache_miss_tokens");
        }
        if (input == null && output == null && prompt == null && completion == null && cacheRead == null
                && cacheCreation == null) {
            return Optional.empty();
        }
        String modelId = usage.path("model").asText(null);
        return Optional.of(new UsageObservation(modelId != null && !modelId.isBlank() ? modelId : "unknown",
                prompt != null ? prompt : input, completion != null ? completion : output, cacheRead, cacheCreation,
                null, null, null, UsageSource.PROVIDER_RESPONSE, 1.0));
    }

    /** Parses a DeepSeek response body (OpenAI or Anthropic entry shape). */
    public static Optional<UsageObservation> parseResponse(ObjectMapper objectMapper, byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() && root.has("message")) {
                usage = root.path("message").path("usage");
            }
            return parse(usage.isMissingNode() ? null : usage);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Long longValue(JsonNode usage, String fieldName) {
        JsonNode value = usage.get(fieldName);
        return value != null && value.canConvertToLong() ? value.longValue() : null;
    }
}
