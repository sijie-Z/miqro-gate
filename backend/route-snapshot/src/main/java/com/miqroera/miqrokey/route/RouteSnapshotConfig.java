package com.miqroera.miqrokey.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.route.RouteSnapshotConfig.RouteSnapshotProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.DriverManager;
import java.time.Clock;

/**
 * Wiring for the route snapshot subsystem. Activated by
 * {@code miqrokey.gateway.persistence.enabled=true} (the gateway's database
 * mode). When disabled, tests install an in-memory provider instead.
 *
 * <p>
 * Reuses the gateway's single JDBC template ({@code gatewayJdbcTemplate}, wired
 * by {@code GatewayDataSourceConfig}) so the context holds exactly one
 * {@code NamedParameterJdbcTemplate} — other consumers (e.g. the usage-event
 * writer in queue-spi) resolve it unqualified.
 * </p>
 */
@Configuration
@ConditionalOnProperty(prefix = "miqrokey.gateway.persistence", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({RouteSnapshotProperties.class, PersistenceProperties.class})
public class RouteSnapshotConfig {

    @ConfigurationProperties(prefix = "miqrokey.gateway.route-snapshot")
    public record RouteSnapshotProperties(String refreshInterval, String notifyChannel) {

        public RouteSnapshotProperties {
            notifyChannel = notifyChannel == null || notifyChannel.isBlank() ? "miqrokey_route_refresh" : notifyChannel;
        }
    }

    @Bean
    public JdbcRouteSnapshotLoader routeSnapshotLoader(
            @Qualifier("gatewayJdbcTemplate") NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcRouteSnapshotLoader(jdbc, objectMapper);
    }

    @Bean
    public RouteSnapshotHolder routeSnapshotHolder(Clock clock) {
        return new RouteSnapshotHolder(clock);
    }

    @Bean
    public RouteSnapshotRefresher routeSnapshotRefresher(JdbcRouteSnapshotLoader loader, RouteSnapshotHolder holder,
            Clock clock) {
        return new RouteSnapshotRefresher(loader, holder, clock);
    }

    /**
     * Cross-process refresh listener: a dedicated {@code DriverManager} connection
     * (outside the Hikari pool) blocking on PostgreSQL {@code LISTEN}, refreshing
     * the snapshot on every control-plane {@code pg_notify}. Closed via
     * {@code destroyMethod} on shutdown.
     */
    @Bean(destroyMethod = "close")
    public RouteSnapshotRefreshListener routeSnapshotRefreshListener(RouteSnapshotProperties properties,
            PersistenceProperties persistence, RouteSnapshotRefresher refresher) {
        if (persistence.url() == null) {
            throw new IllegalStateException("miqrokey.gateway.persistence.enabled=true requires "
                    + "miqrokey.gateway.persistence.url (e.g. jdbc:postgresql://localhost:5432/miqrokey)");
        }
        return new RouteSnapshotRefreshListener(properties.notifyChannel(),
                () -> DriverManager.getConnection(persistence.url(), persistence.username(), persistence.password()),
                refresher);
    }
}
