package com.miqroera.miqrokey.controlplane.service;

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
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderProductAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-triggered model probe (#346, I4, raw doc 05): resolves the product's
 * registered adapter and the first ACTIVE credential of the tenant's
 * subscriptions on that product, fetches the official model catalog through the
 * same success-only pipeline as the scheduled refresh, and records the probe
 * outcome on {@code provider_products} (V43) so failures are visible next to
 * the unchanged last-successful catalog.
 */
@Service
public class ModelCatalogProbeService {

    private static final Logger LOG = LoggerFactory.getLogger(ModelCatalogProbeService.class);

    /** Bound for a single probe fetch (admin request thread). */
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_ERROR_CHARS = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProviderProductRepository productRepository;
    private final UpstreamSubscriptionRepository subscriptionRepository;
    private final UpstreamCredentialRepository credentialRepository;
    private final UpstreamCredentialVersionRepository versionRepository;
    private final AdapterRegistry adapterRegistry;
    private final ProviderClientFactory clientFactory;
    private final KeyEncryptionProvider keyEncryptionProvider;
    private final ModelCatalogService catalogService;
    private final AuditService auditService;

    public ModelCatalogProbeService(NamedParameterJdbcTemplate jdbc, ProviderProductRepository productRepository,
            UpstreamSubscriptionRepository subscriptionRepository, UpstreamCredentialRepository credentialRepository,
            UpstreamCredentialVersionRepository versionRepository, AdapterRegistry adapterRegistry,
            ProviderClientFactory clientFactory, KeyEncryptionProvider keyEncryptionProvider,
            ModelCatalogService catalogService, AuditService auditService) {
        this.jdbc = jdbc;
        this.productRepository = productRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.credentialRepository = credentialRepository;
        this.versionRepository = versionRepository;
        this.adapterRegistry = adapterRegistry;
        this.clientFactory = clientFactory;
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.catalogService = catalogService;
        this.auditService = auditService;
    }

    /**
     * Runs a probe for the product and returns the applied catalog report. On any
     * fetch failure the outcome is recorded as FAILED (sanitized message) and the
     * call fails with {@code 502 MODEL_PROBE_FAILED} — the catalog rows are left
     * untouched (success-only pipeline).
     */
    public Map<String, Object> probe(UUID tenantId, UUID adminId, UUID providerProductId, AuditContext context) {
        ProviderProduct product = productRepository.findById(providerProductId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_PROBE_PRODUCT_NOT_FOUND", "供应商产品不存在。"));
        ProviderProductAdapter adapter = adapterRegistry.findById(product.productCode()).orElseThrow(
                () -> new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROBE_ADAPTER_UNAVAILABLE", "该产品没有已注册的适配器。"));
        URI baseUrl = firstBaseUrl(product.baseUrlTemplates());
        if (baseUrl == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROBE_BASE_URL_MISSING", "产品缺少 base URL。");
        }
        UpstreamCredential credential = resolveCredential(tenantId, product.id());
        String secret = decryptSecret(credential, tenantId);

        Instant now = Instant.now();
        try {
            ProviderClient client = clientFactory.create(baseUrl, "Authorization", "Bearer " + secret);
            ModelCatalogSnapshot snapshot = catalogService.probeProduct(adapter, client, FETCH_TIMEOUT);
            recordProbe(product.id(), "SUCCEEDED", null, snapshot.models().size(), now);
            auditService.record(
                    tenantId, adminId, "MODEL_CATALOG_PROBE_SUCCEEDED", "PROVIDER_PRODUCT", product.id(), AuditSummaries
                            .summary("providerProduct", product.productCode(), "modelCount", snapshot.models().size()),
                    context.requestId());
            return report(product, snapshot, now);
        } catch (RuntimeException e) {
            String message = sanitize(e);
            recordProbe(product.id(), "FAILED", message, null, now);
            auditService.record(tenantId, adminId, "MODEL_CATALOG_PROBE_FAILED", "PROVIDER_PRODUCT", product.id(),
                    AuditSummaries.summary("providerProduct", product.productCode(), "error", message),
                    context.requestId());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_PROBE_FAILED", message);
        }
    }

    /** Last probe outcome for the product; all values null when never probed. */
    public Map<String, Object> probeStatus(UUID providerProductId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT model_catalog_probe_status, model_catalog_probe_error, model_catalog_model_count,
                       model_catalog_probed_at
                FROM provider_products WHERE id = :id
                """, new MapSqlParameterSource("id", providerProductId));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MODEL_PROBE_PRODUCT_NOT_FOUND", "供应商产品不存在。");
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", row.get("model_catalog_probe_status"));
        view.put("error", row.get("model_catalog_probe_error"));
        view.put("modelCount", row.get("model_catalog_model_count"));
        Object probedAt = row.get("model_catalog_probed_at");
        view.put("probedAt", probedAt == null ? null : ((java.sql.Timestamp) probedAt).toInstant().toString());
        return view;
    }

    private void recordProbe(UUID productId, String status, String error, Integer modelCount, Instant at) {
        jdbc.update("""
                UPDATE provider_products
                SET model_catalog_probe_status = :status, model_catalog_probe_error = :error,
                    model_catalog_model_count = :count, model_catalog_probed_at = :at, updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource("status", status).addValue("error", error).addValue("count", modelCount)
                .addValue("at", java.sql.Timestamp.from(at)).addValue("id", productId));
    }

    /**
     * First ACTIVE credential of the tenant's subscriptions on this product
     * (deterministic order).
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
        throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROBE_CREDENTIAL_UNAVAILABLE", "该产品没有可用的 ACTIVE 上游凭证。");
    }

    /** Decrypts the ACTIVE credential version; the plaintext is wiped after use. */
    private String decryptSecret(UpstreamCredential credential, UUID tenantId) {
        UpstreamCredentialVersion active = versionRepository.findActiveByCredentialId(credential.id())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROBE_CREDENTIAL_UNAVAILABLE",
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
            LOG.warn("Model probe could not decrypt credential {}", credential.id());
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROBE_CREDENTIAL_UNAVAILABLE", "上游凭证不可用。");
        }
    }

    private static Map<String, Object> report(ProviderProduct product, ModelCatalogSnapshot snapshot, Instant now) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("providerProductId", product.id());
        report.put("productCode", product.productCode());
        report.put("modelCount", snapshot.models().size());
        report.put("probedAt", now.toString());
        report.put("models", snapshot.models().stream()
                .map(model -> Map.of("modelId", model.id(), "displayName", model.displayName())).toList());
        return report;
    }

    /** Keeps only the exception message, bounded and stripped of any URL. */
    private static String sanitize(RuntimeException e) {
        String message = String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        message = message.replaceAll("https?://\\S+", "<url>");
        return message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) : message;
    }

    private static URI firstBaseUrl(String baseUrlTemplates) {
        if (baseUrlTemplates == null || baseUrlTemplates.isBlank()) {
            return null;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(baseUrlTemplates);
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
