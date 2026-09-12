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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

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
 * <li>I19: durably written batches are optionally fanned out to webhook /
 * syslog sinks ({@code miqrokey.gateway.mcp-log.forward.*}).</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({McpAccessLogConfig.McpAccessLogProperties.class,
        McpAccessLogConfig.McpLogForwardProperties.class})
public class McpAccessLogConfig {

    /**
     * #401 forwarder deadlines: the webhook deadline covers its own connect +
     * request bounds (configured timeout + headroom); the syslog deadline covers a
     * 3s connect plus the write that otherwise has no timeout at all.
     */
    static final long WEBHOOK_DEADLINE_HEADROOM_MS = 5_000;

    static final long SYSLOG_DEADLINE_MS = 15_000;

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

    /**
     * External delivery (I19, Tencent raw 16 log shipping):
     * {@code miqrokey.gateway.mcp-log.forward.*}. Both sinks are off by default
     * (blank host/url); when configured they receive each durably written batch as
     * {@code aigw.mcp.*} metadata over HTTP JSON / RFC 5424.
     */
    @ConfigurationProperties(prefix = "miqrokey.gateway.mcp-log.forward")
    public record McpLogForwardProperties(@DefaultValue("") String webhookUrl, @DefaultValue("") String webhookToken,
            @DefaultValue("5000") long webhookTimeoutMs, @DefaultValue("") String syslogHost,
            @DefaultValue("514") int syslogPort, @DefaultValue("UDP") String syslogProtocol,
            @DefaultValue("LOCAL0") String syslogFacility) {
    }

    /** Postgres-backed wiring; requires the gateway DataSource. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "miqrokey.gateway.persistence.enabled", havingValue = "true")
    public static class PostgresLogConfig {

        @Bean
        McpAccessLogWriter mcpAccessLogWriter(NamedParameterJdbcTemplate gatewayJdbcTemplate) {
            return new PostgresMcpAccessLogWriter(gatewayJdbcTemplate);
        }

        @Bean
        List<McpAccessLogForwarder> mcpAccessLogForwarders(McpLogForwardProperties props, ObjectMapper objectMapper) {
            List<McpAccessLogForwarder> forwarders = new ArrayList<>();
            if (props.webhookUrl() != null && !props.webhookUrl().isBlank()) {
                forwarders.add(new TimeBoundedForwarder(
                        new WebhookMcpAccessLogForwarder(props.webhookUrl(), props.webhookToken(),
                                props.webhookTimeoutMs(), objectMapper),
                        props.webhookTimeoutMs() + WEBHOOK_DEADLINE_HEADROOM_MS));
            }
            if (props.syslogHost() != null && !props.syslogHost().isBlank()) {
                forwarders
                        .add(new TimeBoundedForwarder(
                                new SyslogMcpAccessLogForwarder(props.syslogHost(), props.syslogPort(),
                                        props.syslogProtocol(), props.syslogFacility(), objectMapper),
                                SYSLOG_DEADLINE_MS));
            }
            return List.copyOf(forwarders);
        }

        @Bean(destroyMethod = "close")
        McpAccessLogSink mcpAccessLogSink(McpAccessLogProperties props, McpAccessLogWriter mcpAccessLogWriter,
                List<McpAccessLogForwarder> mcpAccessLogForwarders) {
            return new McpAccessLogQueue(props.capacity(), props.flushIntervalMs(), mcpAccessLogWriter,
                    mcpAccessLogForwarders);
        }
    }

    /** No-op fallback used when persistence is off (default). */
    @Bean
    @ConditionalOnMissingBean
    McpAccessLogSink noopMcpAccessLogSink() {
        return new NoopMcpAccessLogSink();
    }
}
