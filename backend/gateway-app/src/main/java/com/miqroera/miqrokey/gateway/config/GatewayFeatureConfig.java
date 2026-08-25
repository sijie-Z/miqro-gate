package com.miqroera.miqrokey.gateway.config;

import com.miqroera.miqrokey.cache.CacheConfig;
import com.miqroera.miqrokey.cache.GatewayResponseCache;
import com.miqroera.miqrokey.cache.NoopCacheProvider;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.proxy.CredentialInjector;
import com.miqroera.miqrokey.gateway.proxy.JdbcCredentialInjector;
import com.miqroera.miqrokey.queue.QueueConfig;
import com.miqroera.miqrokey.route.RouteSnapshotConfig;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;

/**
 * Feature wiring of the gateway runtime: imports the route-snapshot, cache and
 * queue SPI configurations, and supplies gateway-side fallbacks.
 *
 * <ul>
 * <li><b>Route snapshot</b>: when database persistence is disabled, falls back
 * to an always-empty provider — the gateway boots and fails closed (every key
 * is unknown → 404). Tests install an in-memory provider instead.</li>
 * <li><b>Credential injection</b>: the JDBC injector is the production default;
 * it backs off when a test supplies a fixed-value injector.</li>
 * <li><b>Credential decryption</b> runs on a dedicated bounded-elastic
 * scheduler, never on the Reactor event loop.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@Import({RouteSnapshotConfig.class, CacheConfig.class, QueueConfig.class})
public class GatewayFeatureConfig {

    /**
     * Fail-closed fallback: without route data every presented key is unknown and
     * every request is rejected with VIRTUAL_KEY_INVALID.
     */
    @Bean
    @ConditionalOnMissingBean
    public RouteSnapshotProvider emptyRouteSnapshotProvider(Clock clock) {
        return () -> RouteSnapshot.empty(0, Instant.EPOCH);
    }

    /**
     * Null-object cache fallback for when the cache subsystem is disabled (ADR-0008
     * default): every lookup is a miss, every store is a no-op, and the proxy
     * pipeline stays identical.
     */
    @Bean
    @ConditionalOnMissingBean
    public GatewayResponseCache noopResponseCache() {
        return new NoopCacheProvider();
    }

    /**
     * Bounded scheduler for blocking credential decryption (JDBC + AES). Never runs
     * on the event loop; see GatewayNoBlockingTest.
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler credentialDecryptScheduler() {
        return Schedulers.newBoundedElastic(4, 100, "credential-decrypt");
    }

    /**
     * Production credential injector (decrypts the ACTIVE credential version).
     * Tests override this bean with a fixed-value injector.
     */
    @Bean
    @ConditionalOnMissingBean
    public CredentialInjector jdbcCredentialInjector(
            ObjectProvider<com.miqroera.miqrokey.route.CredentialSecretLoader> loader,
            Scheduler credentialDecryptScheduler, com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            RouteSnapshotProvider routeSnapshotProvider) {
        return new JdbcCredentialInjector(loader, credentialDecryptScheduler, objectMapper, routeSnapshotProvider);
    }
}
