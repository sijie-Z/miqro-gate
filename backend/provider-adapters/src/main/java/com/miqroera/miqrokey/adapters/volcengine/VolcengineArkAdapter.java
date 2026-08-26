package com.miqroera.miqrokey.adapters.volcengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.common.TransparentResolve;
import com.miqroera.miqrokey.spi.AdapterCapabilities;
import com.miqroera.miqrokey.spi.CredentialCheck;
import com.miqroera.miqrokey.spi.CredentialInjection;
import com.miqroera.miqrokey.spi.CredentialMaterial;
import com.miqroera.miqrokey.spi.InboundRequest;
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ModelDefinition;
import com.miqroera.miqrokey.spi.PlanDataSource;
import com.miqroera.miqrokey.spi.PlanSnapshot;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import com.miqroera.miqrokey.spi.ProviderRequest;
import com.miqroera.miqrokey.spi.RouteContext;
import com.miqroera.miqrokey.spi.SubscriptionContext;
import com.miqroera.miqrokey.spi.SubscriptionKind;
import com.miqroera.miqrokey.spi.TargetRequest;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObserver;
import com.miqroera.miqrokey.spi.UsageSource;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 火山引擎方舟系列适配器（G3.7, {@code docs/provider-catalog.md} §3.7）： 一个参数化实现覆盖签名目录中的 3
 * 个产品预设 —— 方舟 Coding Plan、Agent Plan、 方舟按量 API。上游 origin 来自路由上下文的已配置产品 Base
 * URL。
 *
 * <p>
 * 官方资料（2026-08-26 核验 volcengine.com，真实凭证联调前状态为 {@code IMPLEMENTED} /
 * {@code WAITING_FOR_CREDENTIAL}）：
 * <ul>
 * <li>Coding Plan：<a href= "https://www.volcengine.com/article/38138">38138</a>
 * —— Anthropic base {@code https://ark.cn-beijing.volces.com/api/coding}；OpenAI
 * base {@code https://ark.cn-beijing.volces.com/api/coding/v3}。模型
 * Doubao-Seed-Code / GLM-4.7 / DeepSeek-V3.2 / Kimi-K2.5，或
 * {@code ark-code-latest}（Auto 模式，控制台切模型 3–5 分钟生效）；额度按 5h/周/月周期刷新；Key 在控制台「API
 * Key 管理」创建，仅限官方支持的 AI 编程 工具使用（违规调用可能停用订阅）。</li>
 * <li>Agent Plan：专属端点 Anthropic
 * {@code https://ark.cn-beijing.volces.com/api/plan}、OpenAI
 * {@code https://ark.cn-beijing.volces.com/api/plan/v3}（活动页为 JS 渲染无法 直接核验，端点经
 * cc-switch 社区预设修正
 * <a href="https://github.com/farion1231/cc-switch/pull/4826">PR #4826</a> 确认，
 * 待真实凭证核验）；覆盖超全模态模型（DeepSeek-V4 系列、GLM-5.1、ArkClaw）。</li>
 * <li>按量 API：方舟在线推理 base {@code https://ark.cn-beijing.volces.com/api/v3} （不消耗
 * Coding/Agent Plan 额度）。</li>
 * </ul>
 *
 * <p>
 * 三个产品均无确认的官方余额/用量查询 API（控制台可见）→ {@link #fetchPlanStatus} 按契约 §6 权威级别返回
 * {@code UNAVAILABLE}，不发起 HTTP 调用，也不以本地估算冒充官方值。OpenAI base 以 {@code /v3} 结尾 →
 * 剥离 SDK {@code /v1} 前缀；Anthropic 路径 {@code /v1/messages} 保留。
 * </p>
 */
public final class VolcengineArkAdapter implements ProviderProductAdapter {

    /**
     * Per-product wire contract. All three products strip the OpenAI SDK
     * {@code /v1} prefix (their OpenAI bases end in {@code /v3} or {@code /v2});
     * Anthropic Messages paths are never rewritten.
     */
    record ProductConfig(String adapterId, String displayName, SubscriptionKind subscriptionKind,
            Set<ProtocolFamily> protocols, boolean teamPlan) {

        ProductConfig {
            if (adapterId == null || adapterId.isBlank() || displayName == null || displayName.isBlank()
                    || subscriptionKind == null || protocols == null || protocols.isEmpty()) {
                throw new IllegalArgumentException("adapterId/displayName/subscriptionKind/protocols are required");
            }
            protocols = Set.copyOf(protocols);
        }

        String modelsPath() {
            return "/models";
        }
    }

    private final ProductConfig config;
    private final ObjectMapper objectMapper;

    private VolcengineArkAdapter(ProductConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** 方舟 Coding Plan（INDIVIDUAL_PLAN，专属端点）。 */
    public static VolcengineArkAdapter codingPlan(ObjectMapper objectMapper) {
        return new VolcengineArkAdapter(
                new ProductConfig("volcengine-coding-plan", "火山引擎方舟 Coding Plan", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false),
                objectMapper);
    }

    /** 方舟 Agent Plan（INDIVIDUAL_PLAN，专属端点，共享套餐额度）。 */
    public static VolcengineArkAdapter agentPlan(ObjectMapper objectMapper) {
        return new VolcengineArkAdapter(
                new ProductConfig("volcengine-agent-plan", "火山引擎方舟 Agent Plan", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false),
                objectMapper);
    }

    /** 方舟按量 API（PAYG，在线推理）。 */
    public static VolcengineArkAdapter paygApi(ObjectMapper objectMapper) {
        return new VolcengineArkAdapter(new ProductConfig("volcengine-payg-api", "火山引擎方舟按量 API", SubscriptionKind.PAYG,
                Set.of(ProtocolFamily.OPENAI_COMPATIBLE), false), objectMapper);
    }

    @Override
    public String adapterId() {
        return config.adapterId();
    }

    @Override
    public Set<ProtocolFamily> protocols() {
        return config.protocols();
    }

    @Override
    public TargetRequest resolve(RouteContext route, InboundRequest request) {
        return new TargetRequest(request.method(), route.baseUrl(), normalizedPath(route, request.path()),
                TransparentResolve.queryString(request.query()),
                TransparentResolve.headers(request, credentialInjection(null).stripInboundHeaders()));
    }

    /**
     * Strips the OpenAI SDK {@code /v1} prefix (the Ark OpenAI bases end in
     * {@code /v3}); Anthropic Messages paths are never rewritten.
     */
    private String normalizedPath(RouteContext route, String path) {
        if (route.protocol() == ProtocolFamily.OPENAI_COMPATIBLE && path.startsWith("/v1/")) {
            return path.substring(3);
        }
        return path;
    }

    @Override
    public CredentialInjection credentialInjection(CredentialMaterial credential) {
        return new CredentialInjection("Authorization", "Bearer ", Set.of("authorization", "x-api-key", "api-key"));
    }

    @Override
    public Mono<CredentialCheck> validateCredential(ProviderClient client) {
        return client.exchange(ProviderRequest.get(config.modelsPath())).map(response -> {
            Instant checkedAt = Instant.now();
            if (response.isSuccess()) {
                return CredentialCheck.valid(checkedAt);
            }
            String message = switch (response.statusCode()) {
                case 401, 403 -> "credential rejected by Volcengine Ark API";
                case 429 -> "rate limited by Volcengine Ark API";
                default -> "Volcengine Ark API returned HTTP " + response.statusCode();
            };
            return CredentialCheck.invalid(message, checkedAt);
        });
    }

    @Override
    public Mono<ModelCatalogSnapshot> fetchModels(ProviderClient client) {
        return client.exchange(ProviderRequest.get(config.modelsPath())).map(response -> {
            if (!response.isSuccess()) {
                throw new IllegalStateException(
                        config.displayName() + " " + config.modelsPath() + " returned HTTP " + response.statusCode());
            }
            List<ModelDefinition> models = new ArrayList<>();
            try {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode data = root.path("data");
                if (data.isArray()) {
                    for (JsonNode item : data) {
                        String id = item.path("id").asText(null);
                        if (id == null || id.isBlank()) {
                            continue;
                        }
                        String name = item.path("name").asText(null);
                        if (name == null || name.isBlank()) {
                            name = item.path("display_name").asText(null);
                        }
                        models.add(name == null || name.isBlank()
                                ? new ModelDefinition(id)
                                : new ModelDefinition(id, name));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        config.displayName() + " " + config.modelsPath() + " response is not parseable", e);
            }
            return new ModelCatalogSnapshot(config.adapterId(), models, Instant.now());
        });
    }

    @Override
    public UsageObserver createUsageObserver(UsageContext context) {
        return new VolcengineArkUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        // 官方资料（38138 等，2026-08-26 核验）均无余额/用量查询 API（额度仅
        // 控制台可见）→ 按契约 §6 权威级别返回 UNAVAILABLE，不发起 HTTP 调用，
        // 也不以本地估算冒充官方值。
        return Mono.just(new PlanSnapshot(subscription.subscriptionId().toString(), config.subscriptionKind(), null,
                null, null, null, null, null, null, config.teamPlan(), PlanDataSource.UNAVAILABLE, Instant.now()));
    }

    @Override
    public AdapterCapabilities capabilities() {
        boolean plan = config.subscriptionKind() != SubscriptionKind.PAYG;
        return new AdapterCapabilities(true, true, false, plan, config.teamPlan(), false,
                UsageSource.PROVIDER_RESPONSE);
    }
}
