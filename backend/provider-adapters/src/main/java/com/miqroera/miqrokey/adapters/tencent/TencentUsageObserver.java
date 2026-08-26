package com.miqroera.miqrokey.adapters.tencent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.common.TokenUsageParser;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObservation;
import com.miqroera.miqrokey.spi.UsageObserver;

import java.util.Optional;

/**
 * Tencent TokenHub usage observer（G3.2）。SPI 契约：observer 由适配器在请求前
 * 创建，调用方在解析上游响应后通过 {@link #onUsage} 恰好回调一次；观察绝不 改写或阻塞代理字节流。
 *
 * <p>
 * TokenHub 两种入口的 usage 形状沿用 OpenAI/Anthropic 生态约定（与 DeepSeek 相同），解析逻辑共享
 * {@link TokenUsageParser}：
 * <ul>
 * <li>OpenAI 兼容（root {@code usage}）：{@code prompt_tokens} /
 * {@code completion_tokens} / {@code prompt_cache_hit_tokens} /
 * {@code prompt_cache_miss_tokens}。</li>
 * <li>Anthropic Messages（root 或 {@code message.usage}）：{@code input_tokens} /
 * {@code output_tokens} / {@code cache_read_input_tokens} /
 * {@code cache_creation_input_tokens}。</li>
 * </ul>
 * </p>
 */
public final class TencentUsageObserver implements UsageObserver {

    private final UsageContext context;
    private UsageObservation last;

    public TencentUsageObserver(UsageContext context) {
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
