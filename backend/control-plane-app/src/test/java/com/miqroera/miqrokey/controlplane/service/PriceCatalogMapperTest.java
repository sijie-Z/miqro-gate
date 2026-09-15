package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.client.SourceModelPrice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure mapping rules of the price sync (issue #585): prefix filtering, exact
 * suffix resolution, the alias table and unmatched handling.
 */
@DisplayName("Price catalog mapper")
class PriceCatalogMapperTest {

    private static SourceModelPrice quote(String slug) {
        return new SourceModelPrice(slug, new BigDecimal("0.000001"), new BigDecimal("0.000002"), null, null);
    }

    @Test
    @DisplayName("index keeps only the requested provider and lowercases the suffix")
    void indexBySuffixFiltersProvider() {
        Map<String, SourceModelPrice> index = PriceCatalogMapper.indexBySuffix(List.of(quote("DeepSeek/DeepSeek-Flash"),
                quote("openai/gpt-x"), quote("deepseek/deepseek-v4-pro"), quote("deepseek/nested/model")), "deepseek");

        assertThat(index).containsOnlyKeys("deepseek-flash", "deepseek-v4-pro");
    }

    @Test
    @DisplayName("resolve matches exact suffix case-insensitively")
    void resolveExact() {
        Map<String, SourceModelPrice> index = PriceCatalogMapper
                .indexBySuffix(List.of(quote("deepseek/deepseek-v4-pro")), "deepseek");

        assertThat(PriceCatalogMapper.resolve("DeepSeek-V4-Pro", "deepseek", index)).isNotNull();
        assertThat(PriceCatalogMapper.resolve("deepseek-v4-pro", "deepseek", index).slug())
                .isEqualTo("deepseek/deepseek-v4-pro");
    }

    @Test
    @DisplayName("resolve falls back to the alias table (deepseek-flash → deepseek-v4.1-flash)")
    void resolveAlias() {
        Map<String, SourceModelPrice> index = PriceCatalogMapper
                .indexBySuffix(List.of(quote("deepseek/deepseek-v4.1-flash")), "deepseek");

        assertThat(PriceCatalogMapper.resolve("deepseek-flash", "deepseek", index).slug())
                .isEqualTo("deepseek/deepseek-v4.1-flash");
    }

    @Test
    @DisplayName("resolve returns null when nothing matches")
    void resolveUnmatched() {
        Map<String, SourceModelPrice> index = PriceCatalogMapper
                .indexBySuffix(List.of(quote("deepseek/deepseek-v4-pro")), "deepseek");

        assertThat(PriceCatalogMapper.resolve("ghost-model", "deepseek", index)).isNull();
    }

    @Test
    @DisplayName("every mapped product code carries an explicit source prefix")
    void productCodePrefixTable() {
        assertThat(PriceCatalogMapper.PRODUCT_CODE_PREFIX).containsEntry("deepseek-payg-api", "deepseek")
                .containsEntry("moonshot-payg-api", "moonshotai").containsEntry("zhipu-payg-api", "z-ai")
                .containsEntry("minimax-payg-api", "minimax").containsEntry("aliyun-payg-api", "qwen")
                .containsEntry("baidu-payg-api", "baidu").containsEntry("volcengine-payg-api", "volcengine");
    }
}
