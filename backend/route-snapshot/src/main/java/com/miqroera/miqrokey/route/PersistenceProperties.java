package com.miqroera.miqrokey.route;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway database connection settings
 * ({@code miqrokey.gateway.persistence.*}).
 *
 * <p>
 * Shared by the gateway's short-borrow Hikari pool
 * ({@code GatewayDataSourceConfig}) and the route snapshot refresh listener's
 * dedicated {@code DriverManager} connection — the listener must NOT borrow
 * from the pool: {@code LISTEN} pins a connection for the process lifetime,
 * which would starve the pool (pool-size 5 is sized for short borrows).
 * </p>
 */
@ConfigurationProperties(prefix = "miqrokey.gateway.persistence")
public record PersistenceProperties(String url, String username, String password, int poolSize) {

    public PersistenceProperties {
        url = url == null || url.isBlank() ? null : url;
        poolSize = poolSize <= 0 ? 5 : poolSize;
    }
}
