package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.UUID;

/**
 * Validates the Origin header on state-changing API requests using strict
 * parsed scheme/host/port equality against a configured allowlist.
 *
 * <p>
 * Substring matching is never used — the Origin URI is parsed and each
 * component is compared exactly. This prevents bypass via strings like
 * {@code http://localhost.evil.com} matching a localhost allowlist entry.
 * </p>
 *
 * <p>
 * In development mode (non-production), requests with no Origin header or
 * localhost origins are permitted. In production mode, missing, malformed, or
 * unlisted Origin headers cause a 403 rejection.
 * </p>
 */
@Component
public class OriginInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(OriginInterceptor.class);
    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> LOCALHOST_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final AuthProperties authProperties;

    public OriginInterceptor(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String method = request.getMethod();

        if (!STATE_CHANGING_METHODS.contains(method)) {
            return true;
        }
        String path = RequestPaths.lookupPath(request);
        if (!path.startsWith("/api/")) {
            return true;
        }
        // The lookup path is decoded, so "%0A" arrives here as a real newline and
        // would forge an extra log line. Flatten before logging (same rule as
        // ApiKeyAuthFilter.forLog).
        String loggedPath = forLog(path);

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            if (authProperties.isProduction()) {
                LOG.warn("Missing Origin header on state-changing request {} {}", method, loggedPath);
                sendRejection(response, "Origin header required", resolveRequestId(request));
                return false;
            }
            // In dev mode, allow missing Origin (server-to-server or local tooling)
            return true;
        }

        // Parse the Origin URI
        URI originUri;
        try {
            originUri = new URI(origin);
        } catch (URISyntaxException e) {
            LOG.warn("Malformed Origin header: {} for {} {}", forLog(origin), method, loggedPath);
            sendRejection(response, "Invalid Origin header format", resolveRequestId(request));
            return false;
        }

        String scheme = originUri.getScheme();
        String host = originUri.getHost();
        int port = originUri.getPort();

        if (scheme == null || host == null) {
            LOG.warn("Origin missing scheme or host: {}", forLog(origin));
            sendRejection(response, "Origin must include scheme and host", resolveRequestId(request));
            return false;
        }

        // Scheme must be http or https
        if (!scheme.equals("http") && !scheme.equals("https")) {
            LOG.warn("Unsupported Origin scheme: {}", scheme);
            sendRejection(response, "Unsupported Origin scheme", resolveRequestId(request));
            return false;
        }

        // Build normalized form: scheme://host[:port]
        String normalized;
        if (port != -1 && !(scheme.equals("http") && port == 80) && !(scheme.equals("https") && port == 443)) {
            normalized = scheme + "://" + host + ":" + port;
        } else {
            normalized = scheme + "://" + host;
        }

        // Check allowlist (exact match)
        for (String allowed : authProperties.getOriginAllowlist()) {
            if (allowed.equals(normalized)) {
                return true;
            }
        }

        // In non-production mode, also allow localhost
        if (!authProperties.isProduction() && LOCALHOST_HOSTS.contains(host)) {
            return true;
        }

        LOG.warn("Origin not in allowlist: {} (normalized: {}) for {} {}", forLog(origin), forLog(normalized), method,
                loggedPath);
        sendRejection(response, "Origin not allowed", resolveRequestId(request));
        return false;
    }

    private void sendRejection(HttpServletResponse response, String title, String requestId) {
        try {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.getWriter().write(ProblemJson.of(403, title, "ORIGIN_REJECTED", null, requestId));
        } catch (Exception e) {
            LOG.warn("Failed to write Origin rejection response", e);
        }
    }

    /**
     * Flattens control, line-separator and paragraph-separator characters so a
     * crafted value cannot forge extra log lines. Mirrors
     * {@code ApiKeyAuthFilter.forLog} — the request path is decoded before it
     * reaches these sinks, so it is caller-controlled text like any header.
     */
    private static String forLog(String value) {
        return value == null ? "?" : value.replaceAll("[\\p{C}\\p{Zl}\\p{Zp}]", "?");
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        if (header != null && !header.isBlank())
            return header;
        return UUID.randomUUID().toString();
    }
}
