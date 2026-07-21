package com.miqroera.miqrokey.gateway.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Configuration for the transparent proxy's upstream target.
 *
 * <p>
 * In G0.2, this is a single fixed in-memory URL (no database routing). Future
 * Goals will replace this with versioned route snapshots.
 * </p>
 */
@ConfigurationProperties(prefix = "miqrokey.gateway.upstream")
public record ProxyTargetProperties(String url, Duration connectTimeout, Duration responseTimeout,
        DataSize maxProxyBuffer) {

    public ProxyTargetProperties {
        url = url == null || url.isBlank() ? null : url;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofMinutes(10) : responseTimeout;
        maxProxyBuffer = maxProxyBuffer == null || maxProxyBuffer.toBytes() <= 0
                ? DataSize.ofKilobytes(256)
                : maxProxyBuffer;
    }
}
