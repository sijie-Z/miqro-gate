package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.client.SourceModelPrice;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping rules between the public price source and our catalog (issue
 * #585): product code → source prefix, model-id suffix matching, and the small
 * known alias table. Kept free of I/O so the rules are unit-testable without a
 * database or network.
 */
final class PriceCatalogMapper {

    /**
     * Our PAYG product codes → price-source provider prefix (OpenRouter). Codes
     * outside this table are reported as skipped instead of guessed: a wrong prefix
     * would silently price a product with another vendor's numbers.
     */
    static final Map<String, String> PRODUCT_CODE_PREFIX = Map.of("deepseek-payg-api", "deepseek", //
            "moonshot-payg-api", "moonshotai", //
            "zhipu-payg-api", "z-ai", //
            "minimax-payg-api", "minimax", //
            "aliyun-payg-api", "qwen", //
            "baidu-payg-api", "baidu", //
            "volcengine-payg-api", "volcengine");

    /**
     * Known model-id aliases where the source slug differs from the vendor's native
     * id (prefix → our model id → source suffix). deepseek-flash is the native V4.1
     * id while the source lists it as deepseek-v4.1-flash.
     */
    static final Map<String, Map<String, String>> MODEL_ALIASES = Map.of(//
            "deepseek", Map.of("deepseek-flash", "deepseek-v4.1-flash"));

    private PriceCatalogMapper() {
    }

    /** Source quotes of one provider, keyed by lowercased slug suffix. */
    static Map<String, SourceModelPrice> indexBySuffix(List<SourceModelPrice> quotes, String sourcePrefix) {
        String prefix = sourcePrefix.toLowerCase(Locale.ROOT) + "/";
        Map<String, SourceModelPrice> index = new HashMap<>();
        for (SourceModelPrice quote : quotes) {
            String slug = quote.slug().toLowerCase(Locale.ROOT);
            if (!slug.startsWith(prefix)) {
                continue;
            }
            String suffix = slug.substring(prefix.length());
            if (!suffix.isBlank() && !suffix.contains("/")) {
                index.putIfAbsent(suffix, quote);
            }
        }
        return index;
    }

    /**
     * Exact suffix match first, then the per-prefix alias table; null = unmatched.
     */
    static SourceModelPrice resolve(String modelId, String sourcePrefix, Map<String, SourceModelPrice> index) {
        String normalized = modelId.toLowerCase(Locale.ROOT);
        SourceModelPrice exact = index.get(normalized);
        if (exact != null) {
            return exact;
        }
        String alias = MODEL_ALIASES.getOrDefault(sourcePrefix, Map.of()).get(normalized);
        return alias == null ? null : index.get(alias.toLowerCase(Locale.ROOT));
    }
}
