package com.miqroera.miqrokey.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * Usage-event bus wiring (queue-spi).
 *
 * <ul>
 * <li>With {@code miqrokey.gateway.persistence.enabled=true}: a bounded
 * {@link PostgresUsageEventBus} drains batches into
 * {@link PostgresUsageEventWriter} (idempotent {@code ON CONFLICT DO NOTHING}
 * writes) on a dedicated bounded writer executor
 * ({@code miqrokey.gateway.queue.writer-threads}, default 4).</li>
 * <li>Otherwise (default, no DataSource): {@link InMemoryUsageEventBus} keeps
 * the gateway testable and the dev loop DB-free.</li>
 * </ul>
 *
 * <p>
 * The coalescer is DISABLED by default (ADR-0008): it is only created when
 * {@code miqrokey.gateway.coalescer.enabled=true}.
 * </p>
 */
@Configuration
@EnableConfigurationProperties({QueueConfig.QueueProperties.class, QueueConfig.CoalescerProperties.class})
public class QueueConfig {

    /** Postgres-backed wiring; requires the gateway DataSource. */
    @Configuration
    @ConditionalOnProperty(name = "miqrokey.gateway.persistence.enabled", havingValue = "true")
    @EnableScheduling
    public static class PostgresBusConfig {

        @Bean
        DataSourceTransactionManager queueTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate queueTransactionTemplate(DataSourceTransactionManager tm) {
            return new TransactionTemplate(tm);
        }

        @Bean
        UsageEventWriter usageEventWriter(NamedParameterJdbcTemplate jdbc,
                TransactionTemplate queueTransactionTemplate) {
            return new PostgresUsageEventWriter(jdbc, queueTransactionTemplate);
        }

        /**
         * Dedicated bounded writer executor (CLAUDE.md: usage persistence is written in
         * a dedicated bounded executor). Flushes run here, never on the shared
         * scheduling thread, so a slow database cannot delay other scheduled work
         * (route-snapshot refresh).
         */
        @Bean(destroyMethod = "dispose")
        Scheduler usageWriterScheduler(QueueProperties props) {
            return Schedulers.newBoundedElastic(props.writerThreads(), 100, "usage-writer");
        }

        @Bean
        UsageEventBus usageEventBus(UsageEventWriter usageEventWriter, QueueProperties props, Clock clock,
                Scheduler usageWriterScheduler) {
            return new PostgresUsageEventBus(props.capacity(), props.flushThreshold(), usageEventWriter,
                    usageWriterScheduler, clock, props.saturationMode(), props.writeThroughTimeout());
        }
    }

    /** In-memory fallback used when persistence is off (default). */
    @Bean
    @ConditionalOnMissingBean
    UsageEventBus inMemoryUsageEventBus(QueueProperties props) {
        return new InMemoryUsageEventBus(props.capacity());
    }

    /** Coalescer: opt-in only (ADR-0008). */
    @Bean
    @ConditionalOnProperty(name = "miqrokey.gateway.coalescer.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    RequestCoalescer inProcessRequestCoalescer() {
        return new InProcessRequestCoalescer();
    }

    @Bean
    @ConditionalOnProperty(name = "miqrokey.gateway.coalescer.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    Duration coalescerWaitTimeout(CoalescerProperties props) {
        return props.waitTimeout();
    }

    /** Bounded-queue tuning: {@code miqrokey.gateway.queue.*}. */
    @ConfigurationProperties(prefix = "miqrokey.gateway.queue")
    public record QueueProperties(@DefaultValue("10000") int capacity, @DefaultValue("100") int flushThreshold,
            @DefaultValue("4") int writerThreads, @DefaultValue("DROP") SaturationMode saturationMode,
            @DefaultValue("5s") Duration writeThroughTimeout) {

        public QueueProperties {
            if (capacity <= 0) {
                throw new IllegalArgumentException("miqrokey.gateway.queue.capacity must be > 0");
            }
            if (flushThreshold <= 0) {
                throw new IllegalArgumentException("miqrokey.gateway.queue.flush-threshold must be > 0");
            }
            if (writerThreads <= 0) {
                throw new IllegalArgumentException("miqrokey.gateway.queue.writer-threads must be > 0");
            }
            if (saturationMode == null) {
                throw new IllegalArgumentException(
                        "miqrokey.gateway.queue.saturation-mode must be DROP or WRITE_THROUGH");
            }
            if (writeThroughTimeout == null || writeThroughTimeout.isNegative() || writeThroughTimeout.isZero()) {
                throw new IllegalArgumentException("miqrokey.gateway.queue.write-through-timeout must be positive");
            }
        }
    }

    /** Coalescer tuning: {@code miqrokey.gateway.coalescer.*}. */
    @ConfigurationProperties(prefix = "miqrokey.gateway.coalescer")
    public record CoalescerProperties(@DefaultValue("2s") Duration waitTimeout) {
    }
}
