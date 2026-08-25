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

import java.time.Clock;

/**
 * Wiring for the route snapshot subsystem. Activated by
 * {@code miqrokey.gateway.persistence.enabled=true} (the gateway's database
 * mode). When disabled, tests install an in-memory provider instead.
 *
 * <p>
 * Bean names are prefixed ({@code routeSnapshotJdbcTemplate}) so this
 * configuration never collides with the control-plane's beans, and so the
 * gateway can co-exist with other JDBC users in the same context.
 * </p>
 */
@Configuration
@ConditionalOnProperty(prefix = "miqrokey.gateway.persistence", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RouteSnapshotProperties.class)
public class RouteSnapshotConfig {

    @ConfigurationProperties(prefix = "miqrokey.gateway.route-snapshot")
    public record RouteSnapshotProperties(String refreshInterval) {
    }

    @Bean(name = "routeSnapshotJdbcTemplate")
    public NamedParameterJdbcTemplate routeSnapshotJdbcTemplate(javax.sql.DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public JdbcRouteSnapshotLoader routeSnapshotLoader(
            @Qualifier("routeSnapshotJdbcTemplate") NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
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

    @Bean
    public CredentialSecretLoader credentialSecretLoader(
            @Qualifier("routeSnapshotJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider keyEncryptionProvider) {
        return new CredentialSecretLoader(jdbc, keyEncryptionProvider);
    }
}
