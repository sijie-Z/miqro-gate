package com.miqroera.miqrokey.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Price-catalog sync configuration (issue #585). The source URL is a
 * compile-time adapter default (OpenRouter public model index) that operations
 * may override per environment — never accepted from request input.
 */
@ConfigurationProperties(prefix = "miqrokey.price-sync")
public class PriceSyncProperties {

    /** Public price source; defaults to the OpenRouter model index. */
    private String url = "https://openrouter.ai/api/v1/models";

    /** USD→CNY conversion applied to source prices at sync time. */
    private BigDecimal usdCnyRate = new BigDecimal("7.2");

    private Duration connectTimeout = Duration.ofSeconds(10);

    private Duration requestTimeout = Duration.ofSeconds(30);

    /** Bounds the fetched payload (the live index is well under 1 MiB). */
    private int maxBytes = 10 * 1024 * 1024;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public BigDecimal getUsdCnyRate() {
        return usdCnyRate;
    }

    public void setUsdCnyRate(BigDecimal usdCnyRate) {
        this.usdCnyRate = usdCnyRate;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }
}
