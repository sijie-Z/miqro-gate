package com.miqroera.miqrokey.domain.model;

/**
 * The usage dimension a quota rule measures. TOKENS counts every token in the
 * usage event (input + output + cacheRead + cacheCreation — the same total as
 * the personal usage view); REQUESTS counts requests that reached the upstream
 * (cache hits do not consume provider quota).
 */
public enum QuotaMetric {
    TOKENS, REQUESTS
}
