package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.client.ProviderClientFactory;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import com.miqroera.miqrokey.domain.model.ProviderProduct;
import com.miqroera.miqrokey.domain.model.UpstreamCredential;
import com.miqroera.miqrokey.domain.model.UpstreamCredentialVersion;
import com.miqroera.miqrokey.domain.model.UpstreamSubscription;
import com.miqroera.miqrokey.domain.repository.ProviderProductRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamCredentialVersionRepository;
import com.miqroera.miqrokey.domain.repository.UpstreamSubscriptionRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.spi.AdapterRegistry;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderRequest;
import com.miqroera.miqrokey.spi.ProviderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-triggered model test-run (#552, cloud-console "在线调试" parity): resolves
 * the product's registered adapter and the first ACTIVE credential of the
 * tenant's subscriptions on that product (same resolution as
 * {@link ModelCatalogProbeService}) and performs ONE real OpenAI-compatible
 * chat call to the upstream ({@code POST {baseUrl}/chat/completions}) so an
 * administrator can verify a credential×model pair end to end from the console.
 *
 * <p>
 * Hygiene: the prompt and the reply are transient — never persisted, never
 * logged; only metadata reaches the audit chain ({@code MODEL_TEST_RUN} with
 * product/model/status/latency/token counts). The decrypted secret is wiped
 * after use. Errors surface sanitized (no URLs, bounded length).
 * </p>
 */
@Service
public class AdminModelTestRunService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminModelTestRunService.class);

    /** Bound for a single test-run exchange (admin request thread). */
    static final Duration RUN_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_PROMPT_CHARS = 2000;
    static final int MAX_ERROR_CHARS = 500;
    static final int MAX_TOKENS = 256;
    static final String DEFAULT_PROMPT = "请回复OK";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderProductRepository productRepository;
    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final UpstreamCredentialRepository credentialRepository;
    private final UpstreamCredentialVersionRepository versionRepository;
    private final AdapterRegistry adapterRegistry;
    private final ProviderClientFactory clientFactory;
    private final KeyEncryptionProvider keyEncryptionProvider;
    private final AuditService auditService;

    public AdminModelTestRunService(ProviderProductRepository productRepository,
            UpstreamSubscriptionRepository subscriptionRepository, UpstreamCredentialRepository credentialRepository,
            UpstreamCredentialVersionRepository versionRepository, AdapterRegistry adapterRegistry,
            ProviderClientFactory clientFactory, KeyEncryptionProvider keyEncryptionProvider,
            AuditService auditService) {
        this.productRepository = productRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.credentialRepository = credentialRepository;
        this.versionRepository = versionRepository;
        this.adapterRegistry = adapterRegistry;
        this.clientFactory = clientFactory;
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.auditService = auditService;
    }

    /**
     * Performs one chat call for the model through the product's first ACTIVE
     * credential; returns the reply with latency and token usage.
     */
    public Map<String, Object> testRun(UUID tenantId, UUID adminId, UUID providerProductId, String modelId,
            String prompt, AuditContext context) {
        String effectiveModel = modelId == null ? "" : modelId.trim();
        if (effectiveModel.isEmpty() || effectiveModel.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_TEST_RUN_MODEL_INVALID", "请指定有效模型。");
        }
        String effectivePrompt = prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt.trim();
        if (effectivePrompt.length() > MAX_PROMPT_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_TEST_RUN_PROMPT_TOO_LONG",
                    "试调消息过长（≤ " + MAX_PROMPT_CHARS + " 字符）。");
        }

        ProviderProduct product = productRepository.findById(providerProductId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_TEST_RUN_PRODUCT_NOT_FOUND", "供应商产品不存在。"));
        adapterRegistry.findById(product.productCode()).orElseThrow(
                () -> new ApiException(HttpStatus.BAD_REQUEST, "MODEL_TEST_RUN_ADAPTER_UNAVAILABLE", "该产品没有已注册的适配器。"));
        URI baseUrl = firstBaseUrl(product.baseUrlTemplates());
        if (baseUrl == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_TEST_RUN_BASE_URL_MISSING", "产品缺少 base URL。");
        }
        UpstreamCredential credential = resolveCredential(tenantId, product.id());
        String secret = decryptSecret(credential, tenantId);

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(Map.of("model", effectiveModel, "messages",
                    List.of(Map.of("role", "user", "content", effectivePrompt)), "max_tokens", MAX_TOKENS));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MODEL_TEST_RUN_FAILED", "试调请求构建失败。");
        }

        Instant started = Instant.now();
        ProviderResponse response;
        try {
            ProviderClient client = clientFactory.create(baseUrl, "Authorization", "Bearer " + secret);
            response = client.exchange(ProviderRequest.postJson("/chat/completions", payload)).block(RUN_TIMEOUT);
            if (response == null) {
                throw new IllegalStateException("empty upstream response");
            }
        } catch (RuntimeException e) {
            long latencyMs = Duration.between(started, Instant.now()).toMillis();
            String message = sanitizeText(
                    String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    MAX_ERROR_CHARS);
            audit(tenantId, adminId, product, effectiveModel, "FAILED", latencyMs, null, message, context);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_TEST_RUN_FAILED", message);
        }
        long latencyMs = Duration.between(started, Instant.now()).toMillis();
        String bodyText = new String(response.body(), StandardCharsets.UTF_8);
        if (!response.isSuccess()) {
            String message = sanitizeText("上游返回 HTTP " + response.statusCode() + "："
                    + bodyText.substring(0, Math.min(bodyText.length(), 200)), MAX_ERROR_CHARS);
            audit(tenantId, adminId, product, effectiveModel, "HTTP_" + response.statusCode(), latencyMs, null, message,
                    context);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_TEST_RUN_FAILED", message);
        }

        try {
            JsonNode root = MAPPER.readTree(bodyText);
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText(null);
            if (content == null) {
                // Reasoning-style payloads may carry the visible text elsewhere;
                // surface an explicit marker instead of silently returning empty.
                String reasoning = message.path("reasoning_content").asText("");
                content = reasoning.isEmpty() ? "" : "（模型仅返回思考内容，无可见回复）";
            }
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.hasNonNull("completion_tokens")
                    ? usage.get("completion_tokens").asInt()
                    : null;
            Integer totalTokens = usage.hasNonNull("total_tokens") ? usage.get("total_tokens").asInt() : null;
            audit(tenantId, adminId, product, effectiveModel, "SUCCEEDED", latencyMs, totalTokens, null, context);

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("providerProductId", product.id());
            view.put("productCode", product.productCode());
            view.put("modelId", effectiveModel);
            view.put("httpStatus", response.statusCode());
            view.put("latencyMs", latencyMs);
            view.put("content", content);
            view.put("promptTokens", promptTokens);
            view.put("completionTokens", completionTokens);
            view.put("totalTokens", totalTokens);
            return view;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            String message = sanitizeText("上游响应无法解析：" + e.getMessage(), MAX_ERROR_CHARS);
            audit(tenantId, adminId, product, effectiveModel, "UNPARSABLE", latencyMs, null, message, context);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_TEST_RUN_FAILED", message);
        }
    }

    private void audit(UUID tenantId, UUID adminId, ProviderProduct product, String modelId, String status,
            long latencyMs, Integer totalTokens, String error, AuditContext context) {
        List<Object> pairs = new ArrayList<>();
        pairs.add("providerProduct");
        pairs.add(product.productCode());
        pairs.add("modelId");
        pairs.add(modelId);
        pairs.add("status");
        pairs.add(status);
        pairs.add("latencyMs");
        pairs.add(latencyMs);
        if (totalTokens != null) {
            pairs.add("totalTokens");
            pairs.add(totalTokens);
        }
        if (error != null) {
            pairs.add("error");
            pairs.add(error);
        }
        auditService.record(tenantId, adminId, "MODEL_TEST_RUN", "PROVIDER_PRODUCT", product.id(),
                AuditSummaries.summary(pairs.toArray()), context.requestId());
    }

    /**
     * First ACTIVE credential of the tenant's subscriptions on this product
     * (deterministic order; same resolution as the model probe).
     */
    private UpstreamCredential resolveCredential(UUID tenantId, UUID providerProductId) {
        List<UpstreamSubscription> subscriptions = subscriptionRepository.findAllByTenantId(tenantId);
        for (UpstreamSubscription subscription : subscriptions) {
            if (!providerProductId.equals(subscription.providerProductId())) {
                continue;
            }
            List<UpstreamCredential> actives = credentialRepository.findAllBySubscriptionIdAndStatus(subscription.id(),
                    "ACTIVE");
            if (!actives.isEmpty()) {
                return actives.get(0);
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_TEST_RUN_CREDENTIAL_UNAVAILABLE",
                "该产品没有可用的 ACTIVE 上游凭证。");
    }

    /** Decrypts the ACTIVE credential version; the plaintext is wiped after use. */
    private String decryptSecret(UpstreamCredential credential, UUID tenantId) {
        UpstreamCredentialVersion active = versionRepository.findActiveByCredentialId(credential.id())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MODEL_TEST_RUN_CREDENTIAL_UNAVAILABLE",
                        "上游凭证没有 ACTIVE 版本。"));
        try {
            byte[] secret = keyEncryptionProvider.decrypt(
                    new EncryptedSecret(active.encryptedSecret(), active.nonce(), active.encryptionKeyVersion()),
                    tenantId, credential.id());
            try {
                return new String(secret, StandardCharsets.UTF_8);
            } finally {
                SecretWiping.clearArray(secret);
            }
        } catch (Exception e) {
            LOG.warn("Model test-run could not decrypt credential {}", credential.id());
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_TEST_RUN_CREDENTIAL_UNAVAILABLE", "上游凭证不可用。");
        }
    }

    /** Keeps only the message, bounded and stripped of any URL. */
    private static String sanitizeText(String text, int cap) {
        String message = text == null ? "" : text;
        message = message.replaceAll("https?://\\S+", "<url>");
        return message.length() > cap ? message.substring(0, cap) : message;
    }

    private static URI firstBaseUrl(String baseUrlTemplates) {
        if (baseUrlTemplates == null || baseUrlTemplates.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(baseUrlTemplates);
            if (node.isArray() && !node.isEmpty()) {
                String url = node.get(0).path("url").asText(null);
                return url != null ? URI.create(url) : null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
