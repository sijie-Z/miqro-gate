package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Interceptor that enforces {@link RequireRole} annotations on controller
 * methods and denies-by-default all {@code /api/v1/admin/**} paths to
 * non-SYSTEM_ADMIN users. Returns 401/403 with RFC 9457
 * {@code application/problem+json} responses.
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RoleInterceptor.class);

    private final UserContext userContext;

    public RoleInterceptor(UserContext userContext) {
        this.userContext = userContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();

        // Deny-by-default: all /api/v1/admin/** require SYSTEM_ADMIN
        if (path.startsWith("/api/v1/admin/")) {
            if (!userContext.isAuthenticated()) {
                sendProblem(response, 401, "UNAUTHORIZED", "Authentication required", resolveRequestId(request));
                return false;
            }
            if (userContext.getUser().role() != UserRole.SYSTEM_ADMIN) {
                sendProblem(response, 403, "FORBIDDEN", "Admin access requires SYSTEM_ADMIN role",
                        resolveRequestId(request));
                return false;
            }
            return true;
        }

        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        RequireRole classAnnotation = method.getBeanType().getAnnotation(RequireRole.class);
        RequireRole methodAnnotation = method.getMethodAnnotation(RequireRole.class);
        UserRole required = null;
        if (methodAnnotation != null) {
            required = methodAnnotation.value();
        } else if (classAnnotation != null) {
            required = classAnnotation.value();
        }

        if (required == null) {
            return true;
        }

        if (!userContext.isAuthenticated()) {
            sendProblem(response, 401, "UNAUTHORIZED", "Authentication required", resolveRequestId(request));
            return false;
        }

        // SYSTEM_ADMIN can access everything (admin override)
        if (userContext.getUser().role() == UserRole.SYSTEM_ADMIN) {
            return true;
        }

        // Exact role match for non-admin users
        if (userContext.getUser().role() != required) {
            sendProblem(response, 403, "FORBIDDEN", "Insufficient permissions", resolveRequestId(request));
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
            LOG.error("Failed to write role problem response", e);
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
