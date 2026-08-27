package com.miqroera.miqrokey.adapters.aliyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.common.TokenUsageParser;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObservation;
import com.miqroera.miqrokey.spi.UsageObserver;

import java.util.Optional;

/**
 * 阿里云百炼 usage observer（G3.8）。SPI 契约：observer 由适配器在请求前创建， 调用方在解析上游响应后通过
 * {@link #onUsage} 恰好回调一次；观察绝不改写或阻塞 代理字节流。
 *
 * <p>
 * 百炼 Coding Plan / Token Plan 团队版 / 兼容模式的 usage 形状沿用 OpenAI/Anthropic
 * 生态约定，解析逻辑共享 {@link TokenUsageParser} （{@code prompt_tokens} /
 * {@code completion_tokens} / 缓存字段；Anthropic 入口字段同约定）。
 * </p>
 */
public final class AliyunBailianUsageObserver implements UsageObserver {

    private final UsageContext context;
    private UsageObservation last;

    public AliyunBailianUsageObserver(UsageContext context) {
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
