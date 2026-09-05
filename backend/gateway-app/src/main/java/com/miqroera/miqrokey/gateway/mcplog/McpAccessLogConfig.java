package com.miqroera.miqrokey.gateway.mcplog;

import com.miqroera.miqrokey.gateway.config.GatewayDataSourceConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * MCP access log wiring (F15):
 *
 * <ul>
 * <li>With {@code miqrokey.gateway.persistence.enabled=true} (the gateway
 * DataSource exists, see {@link GatewayDataSourceConfig}): a
 * {@link PostgresMcpAccessLogWriter} behind a bounded {@link McpAccessLogQueue}
 * flushes rows on a dedicated scheduler.</li>
 * <li>Otherwise (default): a no-op sink — same configuration that turns off
 * usage persistence.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(McpAccessLogConfig.McpAccessLogProperties.class)
public class McpAccessLogConfig {

    /** Bounded-queue tuning: {@code miqrokey.gateway.mcp-log.*}. */
    @ConfigurationProperties(prefix = "miqrokey.gateway.mcp-log")
    public record McpAccessLogProperties(@DefaultValue("4096") int capacity,
            @DefaultValue("1000") long flushIntervalMs) {

        public McpAccessLogProperties {
            if (capacity <= 0) {
                throw new IllegalArgumentException("miqrokey.gateway.mcp-log.capacity must be > 0");
            }
            if (flushIntervalMs <= 0) {
                throw new IllegalArgumentException("miqrokey.gateway.mcp-log.flush-interval-ms must be > 0");
            }
        }
    }

    /** Postgres-backed wiring; requires the gateway DataSource. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "miqrokey.gateway.persistence.enabled", havingValue = "true")
    public static class PostgresLogConfig {

        @Bean
        McpAccessLogWriter mcpAccessLogWriter(NamedParameterJdbcTemplate gatewayJdbcTemplate) {
            return new PostgresMcpAccessLogWriter(gatewayJdbcTemplate);
        }

        @Bean(destroyMethod = "close")
        McpAccessLogSink mcpAccessLogSink(McpAccessLogProperties props, McpAccessLogWriter mcpAccessLogWriter) {
            return new McpAccessLogQueue(props.capacity(), props.flushIntervalMs(), mcpAccessLogWriter);
        }
    }

    /** No-op fallback used when persistence is off (default). */
    @Bean
    @ConditionalOnMissingBean
    McpAccessLogSink noopMcpAccessLogSink() {
        return new NoopMcpAccessLogSink();
    }
}
