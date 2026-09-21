package com.miqroera.miqrokey.controlplane.client;

import java.math.BigDecimal;

/**
 * One model quote from the public price source. Costs are per TOKEN in the
 * source currency (USD for OpenRouter); the sync service converts to the
 * catalog currency (CNY per 1M tokens).
 *
 * @param slug
 *            full source identifier, e.g. {@code deepseek/deepseek-v4.1-flash}
 * @param promptPerToken
 *            input price per token
 * @param completionPerToken
 *            output price per token
 * @param cacheReadPerToken
 *            cache-hit input price per token; null when the source omits it
 * @param cacheWritePerToken
 *            cache-write input price per token; null when the source omits it
 */
public record SourceModelPrice(String slug, BigDecimal promptPerToken, BigDecimal completionPerToken,
        BigDecimal cacheReadPerToken, BigDecimal cacheWritePerToken) {
}
