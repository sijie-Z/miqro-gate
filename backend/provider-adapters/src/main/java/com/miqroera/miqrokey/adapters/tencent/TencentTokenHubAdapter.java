package com.miqroera.miqrokey.adapters.tencent;

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
 * 腾讯云 TokenHub 系列适配器（G3.2, {@code docs/provider-catalog.md} §3.1）：
 * 一个参数化实现覆盖签名目录中的 5 个产品预设 —— Coding Plan、Token Plan 个人版、Token Plan
 * 企业版专业/轻享套餐、TokenHub 按量 API。上游 origin 一律 来自路由上下文的已配置产品 Base
 * URL（管理员按下方官方端点配置），本适配器 只做产品专属的路径归一化、鉴权头处理、模型目录、凭证校验与 Plan 状态。
 *
 * <p>
 * 官方资料（2026-08-25 核验，真实凭证联调前状态为 {@code IMPLEMENTED} /
 * {@code WAITING_FOR_CREDENTIAL}）：
 * <ul>
 * <li>Coding Plan：<a href=
 * "https://cloud.tencent.com/document/product/1823/130092">130092</a> —— OpenAI
 * base {@code https://api.lkeap.cloud.tencent.com/coding/v3}、Anthropic base
 * {@code https://api.lkeap.cloud.tencent.com/coding/anthropic}；Key 形如
 * {@code sk-sp-xxxx}（与按量 {@code sk-xxxx} 不互通）；模型
 * {@code tc-code-latest}/{@code kimi-k2.5}/{@code glm-5}；按请求次数计配额。</li>
 * <li>TokenHub API：<a href=
 * "https://cloud.tencent.com/document/product/1823/130078">130078</a> —— root
 * base {@code https://tokenhub.tencentmaas.com}（广州）/
 * {@code https://tokenhub-intl.tencentmaas.com}（新加坡）；{@code GET /v1/models}
 * 文档形状 {@code data[].id/object/name/created/status}；Bearer Key。</li>
 * <li>Token Plan 个人版：<a href=
 * "https://cloud.tencent.com/document/product/1823/130060">130060</a> —— OpenAI
 * base {@code https://api.lkeap.cloud.tencent.com/plan/v3}；每月 token 池， 每账号仅 1
 * 个专属 Key。</li>
 * <li>Token Plan 企业版专业套餐：<a href=
 * "https://cloud.tencent.com/document/product/1823/130659">130659</a> —— OpenAI
 * base {@code https://tokenhub.tencentmaas.com/plan/v3}；积分池由套餐内多 Key
 * 共享、按调用实时扣减；轻享套餐（
 * <a href="https://cloud.tencent.com/document/product/1823/130661">130661</a>）
 * 同平台，独占额度单位为 token（专业=积分），控制台配置。</li>
 * </ul>
 *
 * <p>
 * 所有产品截至 2026-08 均无官方余额/用量查询 API（仅控制台可见），因此 {@link #fetchPlanStatus} 按契约 §6
 * 权威级别返回 {@code UNAVAILABLE}，绝不冒充 官方值。企业版的多 Key 共享池/独占额度建模见
 * {@code capabilities()} {@code teamPlan} 与
 * {@code PlanSnapshot.sharedPool}。目录声明的 {@code VENDOR_NATIVE}（TokenHub
 * 原生用量端点）为能力预留，官方当前无可用 端点，无对应路由实现。
 * </p>
 */
public final class TencentTokenHubAdapter implements ProviderProductAdapter {

    /**
     * Per-product wire contract. {@code stripOpenAiV1Prefix} is the documented path
     * rule: products whose OpenAI base URL already ends in {@code /v3} (e.g.
     * {@code .../coding/v3}, {@code .../plan/v3}) expect the OpenAI SDK {@code /v1}
     * prefix to be removed ({@code /v1/chat/completions} →
     * {@code /chat/completions}); the root-based TokenHub PAYG base keeps
     * {@code /v1/...} paths verbatim.
     */
    record ProductConfig(String adapterId, String displayName, SubscriptionKind subscriptionKind,
            Set<ProtocolFamily> protocols, boolean stripOpenAiV1Prefix, boolean teamPlan) {

        ProductConfig {
            if (adapterId == null || adapterId.isBlank() || displayName == null || displayName.isBlank()
                    || subscriptionKind == null || protocols == null || protocols.isEmpty()) {
                throw new IllegalArgumentException("adapterId/displayName/subscriptionKind/protocols are required");
            }
            protocols = Set.copyOf(protocols);
        }

        String modelsPath() {
            return stripOpenAiV1Prefix ? "/models" : "/v1/models";
        }
    }

    private final ProductConfig config;
    private final ObjectMapper objectMapper;

    private TencentTokenHubAdapter(ProductConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** 腾讯云 Coding Plan（INDIVIDUAL_PLAN）。 */
    public static TencentTokenHubAdapter codingPlan(ObjectMapper objectMapper) {
        return new TencentTokenHubAdapter(
                new ProductConfig("tencent-coding-plan", "腾讯云 Coding Plan", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES), true, false),
                objectMapper);
    }

    /** 腾讯云 Token Plan 个人版（INDIVIDUAL_PLAN）。 */
    public static TencentTokenHubAdapter tokenPlanPersonal(ObjectMapper objectMapper) {
        return new TencentTokenHubAdapter(
                new ProductConfig("tencent-token-plan-personal", "腾讯云 Token Plan 个人版", SubscriptionKind.INDIVIDUAL_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.VENDOR_NATIVE), true, false),
                objectMapper);
    }

    /** 腾讯云 Token Plan 企业版专业套餐（ENTERPRISE_PLAN，多 Key 共享积分池）。 */
    public static TencentTokenHubAdapter enterprisePro(ObjectMapper objectMapper) {
        return new TencentTokenHubAdapter(
                new ProductConfig("tencent-token-plan-enterprise-pro", "腾讯云 Token Plan 企业版专业套餐",
                        SubscriptionKind.ENTERPRISE_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.VENDOR_NATIVE), true, true),
                objectMapper);
    }

    /** 腾讯云 Token Plan 企业版轻享套餐（ENTERPRISE_PLAN，多 Key 共享 token 池）。 */
    public static TencentTokenHubAdapter enterpriseLite(ObjectMapper objectMapper) {
        return new TencentTokenHubAdapter(
                new ProductConfig("tencent-token-plan-enterprise-lite", "腾讯云 Token Plan 企业版轻享套餐",
                        SubscriptionKind.ENTERPRISE_PLAN,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.VENDOR_NATIVE), true, true),
                objectMapper);
    }

    /** 腾讯云 TokenHub 常规按量 API（PAYG）。 */
    public static TencentTokenHubAdapter paygApi(ObjectMapper objectMapper) {
        return new TencentTokenHubAdapter(
                new ProductConfig("tencent-payg-api", "腾讯云 TokenHub 按量 API", SubscriptionKind.PAYG,
                        Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.VENDOR_NATIVE), false, false),
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
     * Product-specific path normalization (see {@link ProductConfig}): only the
     * OpenAI-compatible family on {@code /v3}-suffixed bases loses the {@code /v1}
     * prefix; Anthropic Messages paths ({@code /v1/messages}) and the PAYG root
     * base are never rewritten.
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
                case 401, 403 -> "credential rejected by Tencent Cloud API";
                case 429 -> "rate limited by Tencent Cloud API";
                default -> "Tencent Cloud API returned HTTP " + response.statusCode();
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
                        // Documented TokenHub shape uses "name" for the display
                        // label; tolerate the OpenAI "display_name" variant.
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
        return new TencentUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        // 官方资料（130078/130092/130060/130659/130661，2026-08-25 核验）：
        // 腾讯云 TokenHub 全产品均无公开余额/用量查询 API（仅控制台），按
        // provider-adapter-contract §6 权威级别返回 UNAVAILABLE —— 不发起任何
        // HTTP 调用，也不以本地估算冒充官方值。
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
