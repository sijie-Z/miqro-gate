package com.miqroera.miqrokey.adapters.moonshot;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Moonshot/Kimi 系列适配器（G3.5, {@code docs/provider-catalog.md} §3.5）：
 * 一个参数化实现覆盖签名目录中的 2 个产品预设 —— Kimi Code 会员 Key（个人会员 订阅，非团队产品）与 Moonshot/Kimi 按量
 * API。上游 origin 来自路由上下文的 已配置产品 Base URL。
 *
 * <p>
 * 官方资料（2026-08-26 核验，真实凭证联调前状态为 {@code IMPLEMENTED} /
 * {@code WAITING_FOR_CREDENTIAL}）：
 * <ul>
 * <li>Kimi Code
 * API：<a href= "https://www.kimi.com/code/docs/">kimi.com/code/docs</a> ——
 * OpenAI 兼容 base {@code https://api.kimi.com/coding/v1}（完整
 * {@code .../coding/v1/chat/completions}）；Anthropic 兼容 base
 * {@code https://api.kimi.com/coding/}（完整 {@code .../coding/v1/messages}，
 * Anthropic 路径 {@code /v1/messages} 必须保留）；Key 在
 * {@code https://www.kimi.com/code/console} 创建（最多 5 个、仅创建时显示一次）； 模型
 * {@code k3}/{@code k3-256k}/{@code kimi-for-coding}/
 * {@code kimi-for-coding-highspeed}；每 5 小时约 300–1200 次请求（按档位）、 最大并发 30。Kimi
 * Code 是 Kimi 会员订阅权益，非按量计费 —— 无余额 API。</li>
 * <li>Moonshot 按量余额：<a href=
 * "https://platform.kimi.com/docs/api/balance">platform.kimi.com/docs/api/balance</a>
 * —— {@code GET https://api.moonshot.cn/v1/users/me/balance}，
 * {@code Authorization: Bearer <key>}；响应 {@code data.available_balance}
 * （现金+代金券，单位人民币元；≤ 0 时推理被拒）、{@code data.voucher_balance}、
 * {@code data.cash_balance}。国内站（platform.kimi.com）与国际站 （platform.kimi.ai）的 Key
 * 完全独立，混用返回 401。</li>
 * </ul>
 *
 * <p>
 * 按量产品因此是 G3.x 系列第一个 {@code OFFICIAL_API} 余额来源 （{@code available_balance} →
 * PAYG remaining，已用/周期保持 null 不冒充）； Kimi Code 会员产品按契约 §6 返回
 * {@code UNAVAILABLE}（不发起 HTTP、不以本地 估算冒充）。
 * </p>
 */
public final class MoonshotKimiAdapter implements ProviderProductAdapter {

    /** Official PAYG balance endpoint (Moonshot, 2026-08-26). */
    static final String BALANCE_PATH = "/users/me/balance";

    /**
     * Per-product wire contract. Both official bases end in {@code /v1}
     * ({@code .../coding/v1}, {@code .../moonshot.cn/v1}): the OpenAI SDK
     * {@code /v1} prefix is stripped; the Kimi Code Anthropic base
     * ({@code .../coding/}) keeps {@code /v1/messages} verbatim.
     * {@code balancePath} is {@code null} for subscription products without a
     * balance API.
     */
    record ProductConfig(String adapterId, String displayName, SubscriptionKind subscriptionKind,
            Set<ProtocolFamily> protocols, boolean teamPlan, String balancePath) {

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

    private MoonshotKimiAdapter(ProductConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** Kimi Code 会员 Key（INDIVIDUAL_PLAN，个人会员订阅，非团队产品）。 */
    public static MoonshotKimiAdapter kimiCodeMember(ObjectMapper objectMapper) {
        return new MoonshotKimiAdapter(
                new ProductConfig("moonshot-kimi-code-member", "Kimi Code 会员 Key", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), false, null),
                objectMapper);
    }

    /** Moonshot/Kimi 按量 API（PAYG，官方余额接口）。 */
    public static MoonshotKimiAdapter paygApi(ObjectMapper objectMapper) {
        return new MoonshotKimiAdapter(new ProductConfig("moonshot-payg-api", "Moonshot/Kimi 按量 API",
                SubscriptionKind.PAYG, Set.of(ProtocolFamily.OPENAI_COMPATIBLE), false, BALANCE_PATH), objectMapper);
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
     * Strips the OpenAI SDK {@code /v1} prefix: both OpenAI bases already end in
     * {@code /v1}. The Kimi Code Anthropic base ({@code .../coding/}) expects the
     * client path {@code /v1/messages} verbatim.
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
                case 401, 403 -> "credential rejected by Moonshot API";
                case 429 -> "rate limited by Moonshot API";
                default -> "Moonshot API returned HTTP " + response.statusCode();
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
        return new MoonshotKimiUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        if (config.balancePath() == null) {
            // Kimi Code 会员为订阅制（Kimi 会员权益），官方无余额/用量查询 API
            // （2026-08-26 核验）；按契约 §6 返回 UNAVAILABLE，不发起 HTTP。
            return Mono.just(new PlanSnapshot(subscription.subscriptionId().toString(), config.subscriptionKind(), null,
                    null, null, null, null, null, null, config.teamPlan(), PlanDataSource.UNAVAILABLE, Instant.now()));
        }
        return client.exchange(ProviderRequest.get(config.balancePath())).map(response -> {
            if (!response.isSuccess()) {
                throw new IllegalStateException(
                        config.displayName() + " " + config.balancePath() + " returned HTTP " + response.statusCode());
            }
            BigDecimal available = null;
            try {
                JsonNode root = objectMapper.readTree(response.body());
                available = decimal(root.path("data").path("available_balance"));
            } catch (Exception e) {
                throw new IllegalStateException(
                        config.displayName() + " " + config.balancePath() + " response is not parseable", e);
            }
            Instant now = Instant.now();
            // PAYG: available_balance（现金+代金券，人民币）即剩余可用额度；
            // Moonshot 不提供已用/周期，保持 null 而不是冒充 0。
            return new PlanSnapshot(subscription.subscriptionId().toString(), config.subscriptionKind(), available,
                    null, available, null, null, null, null, config.teamPlan(), PlanDataSource.OFFICIAL_API, now);
        });
    }

    @Override
    public AdapterCapabilities capabilities() {
        boolean plan = config.subscriptionKind() != SubscriptionKind.PAYG;
        return new AdapterCapabilities(true, true, config.balancePath() != null, plan, config.teamPlan(), false,
                UsageSource.PROVIDER_RESPONSE);
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || !value.isValueNode()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
