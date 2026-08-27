package com.miqroera.miqrokey.controlplane.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Fails fast during context initialization — before the application becomes
 * ready — when production-mode security constraints are violated.
 *
 * <p>
 * This validator <strong>never mutates</strong> configuration. If a constraint
 * is violated the context startup is aborted with a clear error message.
 * Previously the validator would auto-enable {@code cookieSecure} at
 * {@code ApplicationReadyEvent} time, leaving a window where the application
 * was ready but insecure. Now startup fails hard before any endpoint accepts
 * traffic.
 * </p>
 *
 * <h3>Production constraints</h3>
 * <ol>
 * <li>{@code cookieSecure} must be {@code true} — refused startup if
 * {@code false}.</li>
 * <li>Origin allowlist must be non-empty.</li>
 * <li>At least one entry must not be localhost.</li>
 * <li>Every accepted production origin must be an exact absolute HTTPS URI with
 * a host component and no path, query, fragment, or userinfo.</li>
 * <li>HTTP origins other than localhost are rejected.</li>
 * </ol>
 */
@Component
public class ProductionStartupValidator {

    private static final Logger LOG = LoggerFactory.getLogger(ProductionStartupValidator.class);
    private static final Set<String> LOCALHOST_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final AuthProperties authProperties;
    private final Environment environment;

    public ProductionStartupValidator(AuthProperties authProperties, Environment environment) {
        this.authProperties = authProperties;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean productionProfileActive = Arrays.asList(environment.getActiveProfiles()).contains("production");
        boolean productionMode = authProperties.isProduction() || productionProfileActive;

        if (!productionMode) {
            return;
        }

        LOG.info("Production mode active — validating security constraints before application is ready");

        // 1. cookieSecure must be true — never auto-enable
        if (!authProperties.isCookieSecure()) {
            throw new IllegalStateException("Production mode requires cookieSecure=true. "
                    + "Set miqrokey.cookie-secure=true (or MIQROKEY_COOKIE_SECURE=true). "
                    + "Refusing to start with insecure cookies in production.");
        }

        // 2. Origin allowlist must be non-empty
        List<String> allowlist = authProperties.getOriginAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            throw new IllegalStateException("Production mode requires a non-empty miqrokey.origin-allowlist. "
                    + "Configure at least one production origin (e.g., https://your-domain.com).");
        }

        // 3. At least one non-localhost entry
        boolean hasNonLocalhost = allowlist.stream().anyMatch(o -> LOCALHOST_HOSTS.stream().noneMatch(d -> {
            try {
                URI uri = new URI(o);
                return d.equals(uri.getHost());
            } catch (URISyntaxException e) {
                return false;
            }
        }));
        if (!hasNonLocalhost) {
            throw new IllegalStateException(
                    "Production mode requires at least one non-localhost entry in miqrokey.origin-allowlist. "
                            + "Current allowlist contains only localhost entries. "
                            + "Add your production origin (e.g., https://your-domain.com).");
        }

        // 4 & 5. Validate each origin entry: must be exact absolute HTTPS with
        // host, no path/query/fragment/userinfo. HTTP non-localhost is rejected.
        for (String origin : allowlist) {
            URI uri;
            try {
                uri = new URI(origin);
            } catch (URISyntaxException e) {
                throw new IllegalStateException(
                        "Production origin allowlist entry '" + origin + "' is not a valid URI: " + e.getMessage());
            }

            if (uri.getScheme() == null) {
                throw new IllegalStateException(
                        "Production origin '" + origin + "' must have an explicit scheme (https://...).");
            }

            if (uri.getHost() == null) {
                throw new IllegalStateException("Production origin '" + origin + "' must have a host component.");
            }

            // Path, query, fragment, userinfo are forbidden — including a bare "/"
            // path produced by a trailing-slash origin like https://example.com/
            if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                throw new IllegalStateException("Production origin '" + origin + "' must not contain a path. "
                        + "Use a bare origin like https://example.com (no trailing slash or path).");
            }
            if (uri.getQuery() != null) {
                throw new IllegalStateException("Production origin '" + origin + "' must not contain a query string.");
            }
            if (uri.getFragment() != null) {
                throw new IllegalStateException("Production origin '" + origin + "' must not contain a fragment.");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalStateException("Production origin '" + origin + "' must not contain userinfo.");
            }

            // Scheme validation
            if ("https".equals(uri.getScheme())) {
                // Valid: exact HTTPS origin
                continue;
            }

            if ("http".equals(uri.getScheme())) {
                if (LOCALHOST_HOSTS.contains(uri.getHost())) {
                    throw new IllegalStateException("Production origin '" + origin
                            + "': localhost HTTP origins are not allowed in production. " + "Use HTTPS origins only.");
                }
                throw new IllegalStateException(
                        "Production origin '" + origin + "': HTTP non-localhost origins are forbidden in production. "
                                + "Use https:// for all production origins.");
            }

            throw new IllegalStateException("Production origin '" + origin + "' has unsupported scheme '"
                    + uri.getScheme() + "'. Only https:// origins are accepted in production.");
        }

        LOG.info("Production security constraints validated: cookieSecure=true, {} origin(s) configured",
                allowlist.size());
    }
}
