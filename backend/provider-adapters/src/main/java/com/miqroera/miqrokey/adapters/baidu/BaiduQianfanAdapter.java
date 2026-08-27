package com.miqroera.miqrokey.adapters.baidu;

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
 * 百度千帆系列适配器（G3.6, {@code docs/provider-catalog.md} §3.6）： 一个参数化实现覆盖签名目录中的 3
 * 个产品预设 —— 千帆 Coding Plan、Token Plan 个人版、千帆按量 API。上游 origin 来自路由上下文的已配置产品 Base
 * URL。
 *
 * <p>
 * 官方资料（2026-08-26 核验 cloud.baidu.com，真实凭证联调前状态为 {@code IMPLEMENTED} /
 * {@code WAITING_FOR_CREDENTIAL}）：
 * <ul>
 * <li>Coding
 * Plan：<a href= "https://cloud.baidu.com/doc/qianfan/s/imlg0beiu">imlg0beiu</a>
 * —— OpenAI base {@code https://qianfan.baidubce.com/v2/coding}（完整
 * {@code .../v2/coding/chat/completions}）；Anthropic base
 * {@code https://qianfan.baidubce.com/anthropic/coding}（完整
 * {@code .../anthropic/coding/v1/messages}）。Coding Plan 专属 API Key 仅可用于
 * 专属接口（错用返回 {@code coding_plan_api_key_not_allowed} /
 * {@code coding_plan_api_key_required}）；模型
 * {@code kimi-k2.5}/{@code deepseek-v3.2}/{@code glm-5}/{@code minimax-m2.5}/
 * {@code ernie-4.5-turbo-20260402}/{@code deepseek-v4-flash}/{@code glm-5.1}，
 * 另可硬编码 {@code qianfan-code-latest}；按请求次数计配额（Lite ~1200/5h， Pro ~6000/5h）。</li>
 * <li>Token Plan
 * 个人版：<a href= "https://cloud.baidu.com/doc/qianfan/s/Dmrabu8b6">Dmrabu8b6</a>
 * —— OpenAI base
 * {@code https://qianfan.baidubce.com/v2/tokenplan/personal}；Anthropic base
 * {@code https://qianfan.baidubce.com/anthropic/tokenplan/personal}。 专属 Key
 * 仅限个人版使用（每账号限购一个套餐）；月度 token 池（Mini 1000万 / Lite 4200万 / Pro 2.3亿 / Max
 * 7亿），模型共享池；错误码 {@code token_quota_exceeded} /
 * {@code token_plan_person_rate_limit_exceeded}。</li>
 * <li>按量 API：千帆 MaaS v2 常规接口，base {@code https://qianfan.baidubce.com/v2}。 截至
 * 2026-08-26 未检索到稳定的官方余额/用量查询 API（控制台配额页可见）→ {@code fetchPlanStatus} 返回
 * {@code UNAVAILABLE}。</li>
 * </ul>
 *
 * <p>
 * 三个产品的 OpenAI base 都不以版本段结尾（{@code /coding}、{@code /tokenplan/personal}、
 * {@code /v2}），OpenAI SDK 的 {@code /v1} 前缀一律剥离；Anthropic 路径
 * {@code /v1/messages} 一律保留。
 * </p>
 */
public final class BaiduQianfanAdapter implements ProviderProductAdapter {

    /**
     * Per-product wire contract. {@code stripOpenAiV1Prefix} is true for every
     * product: none of the Qianfan bases carries the OpenAI SDK {@code /v1}
     * segment.
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

    private BaiduQianfanAdapter(ProductConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** 千帆 Coding Plan（INDIVIDUAL_PLAN，专属服务接口）。 */
    public static BaiduQianfanAdapter codingPlan(ObjectMapper objectMapper) {
        return new BaiduQianfanAdapter(
                new ProductConfig("baidu-coding-plan", "千帆 Coding Plan", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false),
                objectMapper);
    }

    /** 千帆 Token Plan 个人版（INDIVIDUAL_PLAN，月度 token 池）。 */
    public static BaiduQianfanAdapter tokenPlanPersonal(ObjectMapper objectMapper) {
        return new BaiduQianfanAdapter(
                new ProductConfig("baidu-token-plan-personal", "千帆 Token Plan 个人版", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false),
                objectMapper);
    }

    /** 千帆按量 API（PAYG，MaaS v2 常规接口）。 */
    public static BaiduQianfanAdapter paygApi(ObjectMapper objectMapper) {
        return new BaiduQianfanAdapter(new ProductConfig("baidu-payg-api", "千帆按量 API", SubscriptionKind.PAYG,
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
     * Strips the OpenAI SDK {@code /v1} prefix (no Qianfan OpenAI base carries it);
     * Anthropic Messages paths are never rewritten.
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
                case 401, 403 -> "credential rejected by Baidu Qianfan API";
                case 429 -> "rate limited by Baidu Qianfan API";
                default -> "Baidu Qianfan API returned HTTP " + response.statusCode();
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
        return new BaiduQianfanUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        // 官方文档（imlg0beiu / Dmrabu8b6，2026-08-26 核验）均无余额/用量查询
        // API（剩余额度仅订阅管理控制台可见）；按量平台亦未检索到稳定的官方
        // 余额 API → 按契约 §6 权威级别返回 UNAVAILABLE，不发起 HTTP 调用，也
        // 不以本地估算冒充官方值。
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
