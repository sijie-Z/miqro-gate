package com.miqroera.miqrokey.adapters.deepseek;

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
 * DeepSeek 官方按量 API 适配器（G3.1, {@code docs/provider-catalog.md} §3.8）: OpenAI
 * 兼容（{@code /chat/completions}、{@code /models}、{@code /user/balance}） 与
 * Anthropic Messages 兼容入口（{@code /v1/messages}），Base URL
 * {@code https://api.deepseek.com}，Bearer API Key 凭证。
 *
 * <p>
 * 官方资料（2026-08-25 核验）:
 * <ul>
 * <li>模型与价格、Base URL: <a href=
 * "https://api-docs.deepseek.com/zh-cn/quick_start/pricing">api-docs.deepseek.com/zh-cn/quick_start/pricing</a></li>
 * <li>列出模型: <a href=
 * "https://api-docs.deepseek.com/zh-cn/api/list-models">api-docs.deepseek.com/zh-cn/api/list-models</a></li>
 * <li>查询余额: <a href=
 * "https://api-docs.deepseek.com/zh-cn/api/get-user-balance/">api-docs.deepseek.com/zh-cn/api/get-user-balance</a></li>
 * </ul>
 *
 * <p>
 * 无状态、线程安全；注册进编译期 {@code AdapterRegistry}（adapterId 与签名目录
 * {@code deepseek-payg-api} 一致）。HTTP 全部经调用方提供的 {@link ProviderClient}
 * 执行（集中超时/大小上限/SSRF 门控），本适配器不持有任何 HTTP 客户端。
 * </p>
 */
public final class DeepSeekPaygAdapter implements ProviderProductAdapter {

    /** Must equal the signed catalog's {@code adapterId} (deepseek-payg-api). */
    public static final String ADAPTER_ID = "deepseek-payg-api";

    /** Official list-models endpoint (OpenAI-compatible shape). */
    static final String MODELS_PATH = "/models";

    /** Official balance endpoint. */
    static final String BALANCE_PATH = "/user/balance";

    private final ObjectMapper objectMapper;

    public DeepSeekPaygAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public Set<ProtocolFamily> protocols() {
        return Set.of(ProtocolFamily.OPENAI_COMPATIBLE, ProtocolFamily.ANTHROPIC_MESSAGES);
    }

    @Override
    public TargetRequest resolve(RouteContext route, InboundRequest request) {
        // Header sanitization and query re-encoding are shared across adapters
        // (G3.2); the DeepSeek root base keeps every path verbatim.
        return new TargetRequest(request.method(), route.baseUrl(), request.path(),
                TransparentResolve.queryString(request.query()),
                TransparentResolve.headers(request, credentialInjection(null).stripInboundHeaders()));
    }

    @Override
    public CredentialInjection credentialInjection(CredentialMaterial credential) {
        return new CredentialInjection("Authorization", "Bearer ", Set.of("authorization", "x-api-key", "api-key"));
    }

    @Override
    public Mono<CredentialCheck> validateCredential(ProviderClient client) {
        return client.exchange(ProviderRequest.get(MODELS_PATH)).map(response -> {
            Instant checkedAt = Instant.now();
            if (response.isSuccess()) {
                return CredentialCheck.valid(checkedAt);
            }
            String message = switch (response.statusCode()) {
                case 401, 403 -> "credential rejected by DeepSeek API";
                case 429 -> "rate limited by DeepSeek API";
                default -> "DeepSeek API returned HTTP " + response.statusCode();
            };
            return CredentialCheck.invalid(message, checkedAt);
        });
    }

    @Override
    public Mono<ModelCatalogSnapshot> fetchModels(ProviderClient client) {
        return client.exchange(ProviderRequest.get(MODELS_PATH)).map(response -> {
            if (!response.isSuccess()) {
                throw new IllegalStateException("DeepSeek /models returned HTTP " + response.statusCode());
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
                        String displayName = item.path("display_name").asText(null);
                        models.add(displayName == null || displayName.isBlank()
                                ? new ModelDefinition(id)
                                : new ModelDefinition(id, displayName));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("DeepSeek /models response is not parseable", e);
            }
            return new ModelCatalogSnapshot(ADAPTER_ID, models, Instant.now());
        });
    }

    @Override
    public UsageObserver createUsageObserver(UsageContext context) {
        return new DeepSeekUsageObserver(context);
    }

    @Override
    public Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription) {
        return client.exchange(ProviderRequest.get(BALANCE_PATH)).map(response -> {
            if (!response.isSuccess()) {
                throw new IllegalStateException("DeepSeek /user/balance returned HTTP " + response.statusCode());
            }
            BigDecimal total = null;
            try {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode infos = root.path("balance_infos");
                if (infos.isArray() && !infos.isEmpty()) {
                    total = decimal(infos.get(0).path("total_balance"));
                }
            } catch (Exception e) {
                throw new IllegalStateException("DeepSeek /user/balance response is not parseable", e);
            }
            Instant now = Instant.now();
            // PAYG: 余额即剩余可用额度；DeepSeek 不提供已用/周期，保持 null 而不是冒充 0。
            return new PlanSnapshot(subscription.subscriptionId().toString(), SubscriptionKind.PAYG, total, null, total,
                    null, null, null, null, false, PlanDataSource.OFFICIAL_API, now);
        });
    }

    @Override
    public AdapterCapabilities capabilities() {
        return new AdapterCapabilities(true, true, true, false, false, true, UsageSource.PROVIDER_RESPONSE);
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
