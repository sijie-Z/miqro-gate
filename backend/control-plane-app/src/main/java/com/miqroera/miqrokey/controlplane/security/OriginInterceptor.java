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
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            if (authProperties.isProduction()) {
                LOG.warn("Missing Origin header on state-changing request {} {}", method, path);
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
            LOG.warn("Malformed Origin header: {} for {} {}", origin, method, path);
            sendRejection(response, "Invalid Origin header format", resolveRequestId(request));
            return false;
        }

        String scheme = originUri.getScheme();
        String host = originUri.getHost();
        int port = originUri.getPort();

        if (scheme == null || host == null) {
            LOG.warn("Origin missing scheme or host: {}", origin);
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

        LOG.warn("Origin not in allowlist: {} (normalized: {}) for {} {}", origin, normalized, method, path);
        sendRejection(response, "Origin not allowed", resolveRequestId(request));
        return false;
    }

    private void sendRejection(HttpServletResponse response, String title, String requestId) {
        try {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.getWriter().write(String.format(
                    "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":403,\"code\":\"ORIGIN_REJECTED\",\"requestId\":\"%s\"}",
                    escapeJson(title), escapeJson(requestId)));
        } catch (Exception e) {
            LOG.warn("Failed to write Origin rejection response", e);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        if (header != null && !header.isBlank())
            return header;
        return UUID.randomUUID().toString();
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        return sb.toString();
    }
}
