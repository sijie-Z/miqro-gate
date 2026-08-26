package com.miqroera.miqrokey.adapters.aliyun;

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
 * 阿里云百炼 Model Studio 系列适配器（G3.8, {@code docs/provider-catalog.md} §3.2；运行序号对应
 * implementation-plan 原始 G3.5）：一个参数化实现覆盖签名目录 中的 3 个产品预设 —— 百炼 Coding Plan、Token
 * Plan 团队版、百炼按量 API。上游 origin 来自路由上下文的已配置产品 Base URL。
 *
 * <p>
 * 官方资料（2026-08-26 核验 help.aliyun.com，真实凭证联调前状态为 {@code IMPLEMENTED} /
 * {@code WAITING_FOR_CREDENTIAL}）：
 * <ul>
 * <li>Coding Plan：<a href=
 * "https://help.aliyun.com/zh/model-studio/coding-plan">coding-plan</a> ——
 * OpenAI base {@code https://coding.dashscope.aliyuncs.com/v1}；Anthropic base
 * {@code https://coding.dashscope.aliyuncs.com/apps/anthropic}；专属 Key 形如
 * {@code sk-sp-xxxxx}（与按量 {@code sk-xxxxx} 不互通，错用会按量计费）；Pro 每月 ¥200、~6000
 * 请求/5h、45k/周、90k/月；模型
 * {@code qwen3.7-plus}/{@code qwen3.6-plus}/{@code kimi-k2.5}/{@code glm-5}/
 * {@code MiniMax-M2.5}/{@code qwen3.5-plus}/{@code qwen3-max-2026-01-23}/
 * {@code qwen3-coder-next}/{@code qwen3-coder-plus}/{@code glm-4.7}。</li>
 * <li>Token Plan 团队版：<a href=
 * "https://help.aliyun.com/zh/model-studio/more-tools">more-tools</a> —— OpenAI
 * base
 * {@code https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1}；
 * Anthropic base
 * {@code https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic}； 团队专属
 * API Key（管理员在组织成员列表管理）；仅文本生成类模型。</li>
 * <li>按量 API：百炼兼容模式 base
 * {@code https://dashscope.aliyuncs.com/compatible-mode/v1}（普通 {@code sk-xxxxx}
 * Key）。</li>
 * </ul>
 *
 * <p>
 * 三个产品均无确认的官方余额/用量查询 API（控制台可见）→ {@link #fetchPlanStatus} 按契约 §6 权威级别返回
 * {@code UNAVAILABLE}，不发起 HTTP 调用，也不以本地估算冒充官方值。OpenAI base 以 {@code /v1} 结尾 →
 * 剥离 SDK {@code /v1} 前缀；Anthropic 路径 {@code /v1/messages} 保留。 团队版按
 * {@code PER_MEMBER_SUBSCRIPTION_KEY} 建模（{@code teamPlan=true}、
 * {@code sharedPool=true} 表示团队共享额度），成员 Key 拓扑与共享池语义须真实 账号验证后方可标记 VERIFIED。
 * </p>
 */
public final class AliyunBailianAdapter implements ProviderProductAdapter {

    /**
     * Per-product wire contract. All three products strip the OpenAI SDK
     * {@code /v1} prefix (their OpenAI bases end in {@code /v1}); Anthropic
     * Messages paths are never rewritten.
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

    private AliyunBailianAdapter(ProductConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** 百炼 Coding Plan（INDIVIDUAL_PLAN，专属 Key {@code sk-sp-}）。 */
    public static AliyunBailianAdapter codingPlan(ObjectMapper objectMapper) {
        return new AliyunBailianAdapter(
                new ProductConfig("aliyun-coding-plan", "阿里云百炼 Coding Plan", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false),
                objectMapper);
    }

    /**
     * 百炼 Token Plan 团队版（TEAM_PLAN，团队专属 Key，PER_MEMBER_SUBSCRIPTION_KEY）。
     */
    public static AliyunBailianAdapter tokenPlanTeam(ObjectMapper objectMapper) {
        return new AliyunBailianAdapter(
                new ProductConfig("aliyun-token-plan-team", "阿里云百炼 Token Plan 团队版", SubscriptionKind.TEAM_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), true),
                objectMapper);
    }

    /** 百炼按量 API（PAYG，兼容模式）。 */
    public static AliyunBailianAdapter paygApi(ObjectMapper objectMapper) {
        return new AliyunBailianAdapter(new ProductConfig("aliyun-payg-api", "阿里云百炼按量 API", SubscriptionKind.PAYG,
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
     * Strips the OpenAI SDK {@code /v1} prefix (all three Bailian OpenAI bases end
     * in {@code /v1}); Anthropic Messages paths are never rewritten.
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
                case 401, 403 -> "credential rejected by Aliyun Bailian API";
                case 429 -> "rate limited by Aliyun Bailian API";
                default -> "Aliyun Bailian API returned HTTP " + response.statusCode();
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
        return new AliyunBailianUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        // 官方资料（coding-plan / more-tools，2026-08-26 核验）均无余额/用量查询
        // API（控制台可见）→ 按契约 §6 权威级别返回 UNAVAILABLE，不发起 HTTP
        // 调用，也不以本地估算冒充官方值。
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
