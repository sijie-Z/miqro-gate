package com.miqroera.miqrokey.controlplane.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;

/**
 * Interceptor that validates CSRF tokens for state-changing requests.
 *
 * <p>
 * Applies to all POST, PUT, PATCH, DELETE requests under /api/v1/. The client
 * must include a X-CSRF-Token header whose SHA-256 matches the session's
 * csrfDigest.
 * </p>
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(CsrfInterceptor.class);
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Public paths exempt from CSRF (login, bootstrap). */
    private static final Set<String> CSRF_EXEMPT = Set.of("/api/v1/auth/login", "/api/v1/auth/bootstrap");

    private final SessionService sessionService;
    private final UserContext userContext;

    public CsrfInterceptor(SessionService sessionService, UserContext userContext) {
        this.sessionService = sessionService;
        this.userContext = userContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Only check state-changing methods on API paths
        if (!path.startsWith("/api/") || !STATE_CHANGING_METHODS.contains(method)) {
            return true;
        }

        // Exempt public endpoints
        if (CSRF_EXEMPT.contains(path)) {
            return true;
        }

        String requestId = resolveRequestId(request);

        // User must be authenticated
        if (!userContext.isAuthenticated() || userContext.getSession() == null) {
            sendProblem(response, 403, "FORBIDDEN", "Authentication required", requestId);
            return false;
        }

        String headerToken = request.getHeader(CSRF_HEADER);
        byte[] csrfDigest = userContext.getSession().csrfDigest();

        if (!sessionService.verifyCsrf(headerToken, csrfDigest)) {
            LOG.warn("CSRF validation failed for user {}", userContext.getUser().username());
            sendProblem(response, 403, "CSRF_INVALID", "CSRF validation failed", requestId);
            return false;
        }

        return true;
    }

    private void sendProblem(HttpServletResponse response, int status, String code, String title, String requestId) {
        try {
            response.setStatus(status);
            response.setContentType("application/problem+json");
            response.getWriter().write(String.format(
                    "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\",\"requestId\":\"%s\"}",
                    escapeJson(title), status, escapeJson(code), escapeJson(requestId)));
        } catch (Exception e) {
            LOG.error("Failed to write CSRF problem response", e);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
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
