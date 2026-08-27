package com.miqroera.miqrokey.adapters.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.common.TokenUsageParser;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObservation;
import com.miqroera.miqrokey.spi.UsageObserver;

import java.util.Optional;

/**
 * DeepSeek usage observer（G3.1）。SPI 契约：observer 由适配器在请求前创建， 调用方在解析上游响应后通过
 * {@link #onUsage} 恰好回调一次；观察绝不改写或 阻塞代理字节流。
 *
 * <p>
 * DeepSeek 两种入口的 usage 形状（OpenAI 兼容 + Anthropic Messages）与 腾讯云 TokenHub
 * 等大陆供应商共享同一生态约定，解析逻辑统一在 {@link TokenUsageParser}（G3.2 抽取），本类保留公共静态入口以避免破坏
 * 既有调用方：
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
 * 解析器为纯函数，容忍未知字段与缺失字段；解析失败返回空 {@link Optional}， 绝不影响请求。cache 字段映射：命中 →
 * cacheRead，未命中/创建 → cacheCreation。
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

    /** Shared dual-shape parse; see {@link TokenUsageParser#parse}. */
    public static Optional<UsageObservation> parse(JsonNode usage) {
        return TokenUsageParser.parse(usage);
    }

    /**
     * Shared dual-shape response parse; see {@link TokenUsageParser#parseResponse}.
     */
    public static Optional<UsageObservation> parseResponse(ObjectMapper objectMapper, byte[] body) {
        return TokenUsageParser.parseResponse(objectMapper, body);
    }
}
