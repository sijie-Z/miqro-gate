package com.miqroera.miqrokey.gateway.retention;

import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;

/**
 * Retention side-channel wiring (ADR-0014 R2): the sidecar is always present
 * but every capture is gated by the per-tenant retention config in the route
 * snapshot (default OFF). The publisher bean defaults to a no-op (fail closed);
 * a Kafka producer implementation will register itself and win via
 * {@code @Primary} once wired.
 */
@Configuration
@EnableConfigurationProperties(GatewayRetentionConfig.RetentionProperties.class)
public class GatewayRetentionConfig {

    /** Queue tuning: {@code miqrokey.retention.*}. */
    @ConfigurationProperties(prefix = "miqrokey.retention")
    public record RetentionProperties(@DefaultValue("512") int capacity, @DefaultValue("1000") long flushIntervalMs,
            @DefaultValue("100000") int maxTextChars) {
    }

    @Bean
    RetentionSidecar retentionSidecar(RouteSnapshotProvider routeSnapshotProvider,
            ObjectProvider<com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider> cryptoProvider,
            RetentionPublisher retentionPublisher, RetentionProperties props, Clock clock) {
        return new RetentionSidecar(routeSnapshotProvider, cryptoProvider, retentionPublisher, props.capacity(),
                props.flushIntervalMs(), props.maxTextChars(), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    RetentionPublisher noopRetentionPublisher() {
        return new NoopRetentionPublisher();
    }
}
