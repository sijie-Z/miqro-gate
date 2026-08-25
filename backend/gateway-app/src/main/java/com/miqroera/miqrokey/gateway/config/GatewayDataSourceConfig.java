package com.miqroera.miqrokey.gateway.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * Gateway-side JDBC wiring, activated only by
 * {@code miqrokey.gateway.persistence.enabled=true} (default false).
 *
 * <p>
 * The gateway NEVER queries the database on the Reactor hot path: the
 * DataSource here backs the route snapshot refresher, the credential decryptor,
 * the L2 cache, and the usage event writer — all off the event loop. When the
 * flag is off, no DataSource bean exists and the gateway runs fully in-memory
 * (fail-closed authentication, in-memory usage bus, no L2).
 * </p>
 */
@Configuration
@ConditionalOnProperty(prefix = "miqrokey.gateway.persistence", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GatewayDataSourceConfig.PersistenceProperties.class)
public class GatewayDataSourceConfig {

    @ConfigurationProperties(prefix = "miqrokey.gateway.persistence")
    public record PersistenceProperties(String url, String username, String password, int poolSize) {

        public PersistenceProperties {
            url = url == null || url.isBlank() ? null : url;
            poolSize = poolSize <= 0 ? 5 : poolSize;
        }
    }

    /**
     * The single gateway DataSource. HikariCP with a small pool: the gateway's
     * database work is batched (snapshot refresh every 30s, usage flush every 5s,
     * per-request credential decrypts), so a handful of connections suffices.
     */
    @Bean
    public DataSource gatewayDataSource(PersistenceProperties props) {
        if (props.url() == null) {
            throw new IllegalStateException("miqrokey.gateway.persistence.enabled=true requires "
                    + "miqrokey.gateway.persistence.url (e.g. jdbc:postgresql://localhost:5432/miqrokey)");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.url());
        ds.setUsername(props.username());
        ds.setPassword(props.password());
        ds.setMaximumPoolSize(props.poolSize());
        ds.setPoolName("miqrokey-gateway");
        ds.setAutoCommit(true);
        return ds;
    }

    @Bean
    public NamedParameterJdbcTemplate gatewayJdbcTemplate(DataSource gatewayDataSource) {
        return new NamedParameterJdbcTemplate(gatewayDataSource);
    }
}
