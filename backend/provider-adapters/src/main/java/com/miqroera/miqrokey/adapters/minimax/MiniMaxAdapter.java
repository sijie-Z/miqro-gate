package com.miqroera.miqrokey.adapters.minimax;

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
 * MiniMax 系列适配器（G3.4, {@code docs/provider-catalog.md} §3.4）： 一个参数化实现覆盖签名目录中的 3
 * 个产品预设 —— Token Plan 个人订阅、Token Plan for Teams、按量 API。签名目录声明协议族
 * {@code OPENAI_COMPATIBLE} （MiniMax 同时提供 Anthropic 兼容入口
 * {@code https://api.minimax.io/anthropic}， 待目录下一版签名补声明）；上游 origin
 * 来自路由上下文的已配置产品 Base URL。
 *
 * <p>
 * 官方资料（2026-08-26 核验 platform.minimax.io，真实凭证联调前状态为 {@code IMPLEMENTED} /
 * {@code WAITING_FOR_CREDENTIAL}）：
 * <ul>
 * <li>工具接入：<a href=
 * "https://platform.minimax.io/docs/token-plan/other-tools">other-tools</a> ——
 * OpenAI 兼容 base {@code https://api.minimax.io/v1}；Anthropic 兼容 base
 * {@code https://api.minimax.io/anthropic}；Token Plan 专属 Key 形如
 * {@code sk-cp-…}（与按量 API Key 不互通）；当前模型 {@code MiniMax-M3}。</li>
 * <li>OpenAI 兼容模型列表：<a href=
 * "https://platform.minimax.io/docs/api-reference/models/openai/list-models">
 * list-models</a> —— {@code GET https://api.minimax.io/v1/models}，
 * {@code Authorization: Bearer <API_KEY>}，响应
 * {@code data[].id/object/created/owned_by}（无 display name 字段）。</li>
 * <li>Token Plan
 * 概述：<a href= "https://platform.minimax.io/docs/token-plan/intro">intro</a> ——
 * 每个用户在其 加入的每个 Team 中都有独立 Subscription Key；Token Plan 资源配额后自动由共享 Credits
 * 覆盖（Owner 购买 Credits 池，成员经自己的 Subscription Key 消费）。</li>
 * <li>团队版定价：<a href=
 * "https://platform.minimax.io/docs/guides/pricing-token-plan-team">
 * pricing-token-plan-team</a> —— 席位 1:1 分配给成员（可转授，不重置用量）； 未分配席位的成员在开启 Credits
 * 权限后也可消费共享 Credits → 团队建模 {@code PER_MEMBER_SUBSCRIPTION_KEY} + 共享 Credits 池，
 * {@code PlanSnapshot.sharedPool=true}。</li>
 * </ul>
 *
 * <p>
 * docs 索引（llms.txt）截至 2026-08-26 无任何 Token Plan 余额/用量查询 API （额度与钱包余额仅控制台可见）→
 * {@link #fetchPlanStatus} 按契约 §6 权威级别 返回 {@code UNAVAILABLE}，不发起 HTTP
 * 调用，也不以本地估算冒充官方值。
 * </p>
 */
public final class MiniMaxAdapter implements ProviderProductAdapter {

    /**
     * Per-product wire contract. The signed-catalog base URL
     * ({@code https://api.minimax.chat/v1}) and the current official base
     * ({@code https://api.minimax.io/v1}) both end in {@code /v1}: the OpenAI SDK
     * {@code /v1} prefix must be stripped ({@code /v1/chat/completions} →
     * {@code /chat/completions}) so the full endpoint stays
     * {@code .../v1/chat/completions} and {@code .../v1/models}.
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

    private MiniMaxAdapter(ProductConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** MiniMax Token Plan 个人订阅（INDIVIDUAL_PLAN）。 */
    public static MiniMaxAdapter tokenPlanPersonal(ObjectMapper objectMapper) {
        return new MiniMaxAdapter(
                new ProductConfig("minimax-token-plan-personal", "MiniMax Token Plan 个人订阅",
                        SubscriptionKind.INDIVIDUAL_PLAN, Set.of(ProtocolFamily.OPENAI_COMPATIBLE), false),
                objectMapper);
    }

    /**
     * MiniMax Token Plan for Teams（TEAM_PLAN，每成员 Subscription Key + 共享 Credits 池）。
     */
    public static MiniMaxAdapter tokenPlanTeam(ObjectMapper objectMapper) {
        return new MiniMaxAdapter(new ProductConfig("minimax-token-plan-team", "MiniMax Token Plan for Teams",
                SubscriptionKind.TEAM_PLAN, Set.of(ProtocolFamily.OPENAI_COMPATIBLE), true), objectMapper);
    }

    /** MiniMax 按量 API（PAYG）。 */
    public static MiniMaxAdapter paygApi(ObjectMapper objectMapper) {
        return new MiniMaxAdapter(new ProductConfig("minimax-payg-api", "MiniMax 按量 API", SubscriptionKind.PAYG,
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
     * Strips the OpenAI SDK {@code /v1} prefix: the MiniMax base URL already ends
     * in {@code /v1} ({@code https://api.minimax.io/v1}), so the SDK path
     * {@code /v1/chat/completions} maps to {@code /chat/completions}.
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
                case 401, 403 -> "credential rejected by MiniMax API";
                case 429 -> "rate limited by MiniMax API";
                default -> "MiniMax API returned HTTP " + response.statusCode();
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
                        // Documented MiniMax list-models shape has no display
                        // name; tolerate the OpenAI "name" variant.
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
        return new MiniMaxUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        // docs 索引（llms.txt）与 Token Plan/团队版定价页（2026-08-26 核验）均
        // 无余额/用量查询 API（额度、Credits 与钱包余额仅控制台可见）；按
        // provider-adapter-contract §6 权威级别返回 UNAVAILABLE，不发起 HTTP
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
