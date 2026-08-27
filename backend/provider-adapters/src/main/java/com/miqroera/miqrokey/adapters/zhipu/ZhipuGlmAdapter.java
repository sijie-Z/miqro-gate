package com.miqroera.miqrokey.adapters.zhipu;

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
 * 智谱 GLM 系列适配器（G3.3, {@code docs/provider-catalog.md} §3.3）： 一个参数化实现覆盖签名目录中的 3
 * 个产品预设 —— Coding Plan 个人版、Coding Plan 团队版、GLM 开放平台按量 API。上游 origin
 * 来自路由上下文的已配置产品 Base URL（签名目录默认值
 * {@code https://open.bigmodel.cn/api/paas/v4}），本
 * 适配器只做产品专属的路径归一化、鉴权头处理、模型目录、凭证校验与 Plan 状态。
 *
 * <p>
 * 官方资料（2026-08-26 核验，真实凭证联调前状态为 {@code IMPLEMENTED} /
 * {@code WAITING_FOR_CREDENTIAL}）：
 * <ul>
 * <li>Coding Plan 快速开始：<a href=
 * "https://docs.bigmodel.cn/cn/coding-plan/quick-start">quick-start</a> ——
 * OpenAI Chat Completion base
 * {@code https://open.bigmodel.cn/api/coding/paas/v4}（Coding Plan 专属， 与按量 API 的
 * {@code /api/paas/v4} 不同）；Anthropic Messages base
 * {@code https://open.bigmodel.cn/api/anthropic}（完整路径
 * {@code .../api/anthropic/v1/messages}）。个人版 Key 在个人概览页创建；团队版
 * 成员收到席位邀请后从团队套餐界面获取专属 Key，团队 Key 与平台其他 API Key 不通用。</li>
 * <li>套餐概览：<a href=
 * "https://docs.bigmodel.cn/cn/coding-plan/overview">overview</a> —— 积分池（Lite
 * 2000/5h、10k/周；Pro 12k/5h、60k/周；Max 28k/5h、140k/周）， 按抵扣系数扣减，非高峰（周一至周五
 * 14:00～18:00 之外）50% 抵扣；支持模型 GLM-5.3 / GLM-5-Turbo / GLM-4.7（历史模型自动切换至
 * GLM-5.3）。</li>
 * <li>团队版权益：<a href= "https://docs.bigmodel.cn/cn/coding-plan/team">team</a> ——
 * 席位制，2 席起购， 每个席位独立额度（标准版 15k/5h、66k/周；高级版 35k/5h、155k/周），额度按席位 单独限制而非团队共享池 →
 * 团队建模 {@code PER_SEAT_KEY}，
 * {@code PlanSnapshot.sharedPool=false}；管理员可分配席位、设置成员用量上限与 IP 白名单。</li>
 * <li>对话补全
 * API：<a href= "https://docs.bigmodel.cn/api-reference/模型-api/对话补全">对话补全</a> ——
 * 按量入口 {@code https://open.bigmodel.cn/api/paas/v4/chat/completions}，
 * {@code Authorization: Bearer <API_KEY>}；usage 形状
 * {@code prompt_tokens/completion_tokens/total_tokens} +
 * {@code prompt_tokens_details.cached_tokens}（缓存命中）。</li>
 * </ul>
 *
 * <p>
 * 余额与模型列表：docs 索引（llms.txt）截至 2026-08-26 无任何余额/用量查询 API 与模型列表 API ——
 * {@link #fetchPlanStatus} 按契约 §6 权威级别返回 {@code UNAVAILABLE}（不发起
 * HTTP、不以本地估算冒充）；{@code /models} 探活为 OpenAI 兼容惯例端点，官方文档未收录，待真实凭证联调核验（若确认不存在则
 * validateCredential 改用最小推理探针并同步调整文档）。Anthropic 兼容入口官方 文档以 {@code x-api-key}
 * 为例（Anthropic SDK 默认头），本适配器按平台惯例注入 {@code Authorization: Bearer}；兼容性待真实凭证核验，见
 * {@code docs/provider-catalog.md} §3.3 风险说明。
 * </p>
 */
public final class ZhipuGlmAdapter implements ProviderProductAdapter {

    /**
     * Per-product wire contract. {@code stripOpenAiV1Prefix} follows the same rule
     * as Tencent TokenHub: the OpenAI-compatible base URL already ends in
     * {@code /v4}, so the OpenAI SDK {@code /v1} prefix is stripped
     * ({@code /v1/chat/completions} → {@code /chat/completions}); Anthropic
     * Messages paths keep {@code /v1/...}.
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
            return stripOpenAiV1Prefix() ? "/models" : "/v1/models";
        }

        boolean stripOpenAiV1Prefix() {
            // The signed-catalog base URL for all Zhipu products ends in /v4.
            return true;
        }
    }

    private final ProductConfig config;
    private final ObjectMapper objectMapper;

    private ZhipuGlmAdapter(ProductConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** 智谱 GLM Coding Plan 个人版（INDIVIDUAL_PLAN）。 */
    public static ZhipuGlmAdapter codingPlanPersonal(ObjectMapper objectMapper) {
        return new ZhipuGlmAdapter(
                new ProductConfig("zhipu-coding-plan-personal", "GLM Coding Plan 个人版", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false),
                objectMapper);
    }

    /** 智谱 GLM Coding Plan 团队版（TEAM_PLAN，PER_SEAT_KEY）。 */
    public static ZhipuGlmAdapter codingPlanTeam(ObjectMapper objectMapper) {
        return new ZhipuGlmAdapter(
                new ProductConfig("zhipu-coding-plan-team", "GLM Coding Plan 团队版", SubscriptionKind.TEAM_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), true),
                objectMapper);
    }

    /** 智谱 GLM 开放平台按量 API（PAYG）。 */
    public static ZhipuGlmAdapter paygApi(ObjectMapper objectMapper) {
        return new ZhipuGlmAdapter(
                new ProductConfig("zhipu-payg-api", "GLM 开放平台按量 API", SubscriptionKind.PAYG,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false),
                objectMapper);
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
     * Strips the OpenAI SDK {@code /v1} prefix when the base URL already carries
     * the vendor version segment ({@code /v4}); Anthropic Messages paths are never
     * rewritten.
     */
    private String normalizedPath(RouteContext route, String path) {
        if (config.stripOpenAiV1Prefix() && route.protocol() == ProtocolFamily.OPENAI_COMPATIBLE
                && path.startsWith("/v1/")) {
            return path.substring(3);
        }
        return path;
    }

    @Override
    public CredentialInjection credentialInjection(CredentialMaterial credential) {
        // Zhipu is consumed through the OpenAI SDK, which sends
        // Authorization: Bearer <api-key>.
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
                case 401, 403 -> "credential rejected by Zhipu GLM API";
                case 429 -> "rate limited by Zhipu GLM API";
                default -> "Zhipu GLM API returned HTTP " + response.statusCode();
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
        return new ZhipuGlmUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        // docs 索引（llms.txt）与对话补全 API 页面（2026-08-26 核验）均无余额/
        // 用量查询 API（个人/团队额度仅控制台可见）；按 provider-adapter-contract
        // §6 权威级别返回 UNAVAILABLE，不发起 HTTP 调用，也不以本地估算冒充
        // 官方值。
        return Mono.just(new PlanSnapshot(subscription.subscriptionId().toString(), config.subscriptionKind(), null,
                null, null, null, null, null, null, false, PlanDataSource.UNAVAILABLE, Instant.now()));
    }

    @Override
    public AdapterCapabilities capabilities() {
        boolean plan = config.subscriptionKind() != SubscriptionKind.PAYG;
        return new AdapterCapabilities(true, true, false, plan, config.teamPlan(), false,
                UsageSource.PROVIDER_RESPONSE);
    }
}
