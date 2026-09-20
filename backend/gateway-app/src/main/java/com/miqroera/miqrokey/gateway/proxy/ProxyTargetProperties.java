package com.miqroera.miqrokey.gateway.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the transparent proxy's upstream target.
 *
 * <p>
 * Upstream base URLs come from the versioned route snapshot, never from
 * configuration (a user-supplied proxy target would defeat the SSRF guard).
 * What is configured here are only the transport-level knobs shared by every
 * route: timeouts, the parse buffer ceiling, and the non-public CIDRs the SSRF
 * guard may allow.
 * </p>
 */
@ConfigurationProperties(prefix = "miqrokey.gateway.upstream")
public record ProxyTargetProperties(Duration connectTimeout, Duration firstByteTimeout,
        Duration streamIdleTimeout, Duration responseTimeout, DataSize maxProxyBuffer, List<String> allowedCidrs) {

    public ProxyTargetProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        firstByteTimeout = firstByteTimeout == null ? Duration.ofSeconds(120) : firstByteTimeout;
        streamIdleTimeout = streamIdleTimeout == null ? Duration.ofMinutes(5) : streamIdleTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofMinutes(10) : responseTimeout;
        maxProxyBuffer = maxProxyBuffer == null || maxProxyBuffer.toBytes() <= 0
                ? DataSize.ofKilobytes(256)
                : maxProxyBuffer;
        allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
    }
}
