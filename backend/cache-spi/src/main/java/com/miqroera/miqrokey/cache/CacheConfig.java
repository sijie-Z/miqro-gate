package com.miqroera.miqrokey.cache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;

/**
 * Cache subsystem wiring. Activated by {@code miqrokey.cache.enabled=true}
 * (DISABLED by default — ADR-0008).
 *
 * <p>
 * Levels: {@code miqrokey.cache.l1.enabled} (default true) and
 * {@code miqrokey.cache.l2.enabled} (default true). L2 additionally requires
 * the gateway database mode ({@code miqrokey.gateway.persistence.enabled});
 * when L2 is unavailable, L1 runs standalone over a null-object delegate.
 * </p>
 */
@Configuration
@ConditionalOnProperty(prefix = "miqrokey.cache", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CacheConfig.CacheProperties.class)
public class CacheConfig {

    @ConfigurationProperties(prefix = "miqrokey.cache")
    public record CacheProperties(
            @org.springframework.boot.context.properties.bind.DefaultValue("true") boolean l1Enabled,
            @org.springframework.boot.context.properties.bind.DefaultValue("true") boolean l2Enabled,
            @org.springframework.boot.context.properties.bind.DefaultValue("300s") Duration l1Ttl,
            @org.springframework.boot.context.properties.bind.DefaultValue("300s") Duration l2Ttl) {
    }

    @Bean
    public GatewayResponseCache gatewayResponseCache(ObjectProvider<javax.sql.DataSource> dataSource,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper, CacheProperties props) {
        PostgresCacheProvider l2 = null;
        if (props.l2Enabled() && dataSource.getIfAvailable() != null) {
            l2 = new PostgresCacheProvider(new NamedParameterJdbcTemplate(dataSource.getIfAvailable()), objectMapper,
                    props.l2Ttl());
        }
        GatewayResponseCache delegate = l2 != null ? l2 : new NoopCacheProvider();
        if (props.l1Enabled()) {
            return new CaffeineCacheProvider(delegate, props.l1Ttl());
        }
        return delegate;
    }
}
