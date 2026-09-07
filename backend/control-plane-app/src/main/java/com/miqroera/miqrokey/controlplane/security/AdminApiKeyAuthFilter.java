package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Authentication for the open admin surface (ADR-0015), protecting
 * {@code /api/v1/admin-api/**}. A SYSTEM_ADMIN portal session also passes and
 * is treated like a machine key of its own tenant; otherwise the presented
 * {@code Authorization: Bearer mqk_admin_…} credential is matched against the
 * stored SHA-256 digest (revoked keys fail immediately, expiry is checked). The
 * key identity is exposed as request attributes for the open controllers.
 */
public class AdminApiKeyAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated admin key id. */
    public static final String KEY_ATTR = "adminApiKeyId";
    /** Request attribute holding the key's tenant id. */
    public static final String TENANT_ATTR = "adminApiKeyTenantId";
    /** Request attribute holding the key name. */
    public static final String NAME_ATTR = "adminApiKeyName";

    /** Path prefix of the open admin surface. */
    public static final String OPEN_PATH = "/api/v1/admin-api";
    private static final String KEY_PREFIX = "mqk_admin_";

    private final AdminApiKeyRepository repository;
    private final UserContext userContext;

    public AdminApiKeyAuthFilter(AdminApiKeyRepository repository, UserContext userContext) {
        this.repository = repository;
        this.userContext = userContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(OPEN_PATH)) {
            chain.doFilter(request, response);
            return;
        }
        if (userContext.isAuthenticated()) {
            // Portal sessions need SYSTEM_ADMIN on the open surface (parity with
            // /api/v1/admin/**); their tenant seeds the same attribute contract.
            if (userContext.getUser().role() != UserRole.SYSTEM_ADMIN) {
                forbidden(response);
                return;
            }
            request.setAttribute(TENANT_ATTR, userContext.getUser().tenantId());
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7) {
            String token = authorization.substring(7).trim();
            if (token.startsWith(KEY_PREFIX) && authenticate(token, request)) {
                chain.doFilter(request, response);
                return;
            }
        }
        unauthorized(response);
    }

    private boolean authenticate(String token, HttpServletRequest request) {
        AdminApiKey key = repository.findActiveByDigest(sha256(token)).orElse(null);
        if (key == null || !key.active()) {
            return false;
        }
        request.setAttribute(KEY_ATTR, key.id());
        request.setAttribute(TENANT_ATTR, key.tenantId());
        request.setAttribute(NAME_ATTR, key.name());
        return true;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"code\":\"ADMIN_API_KEY_INVALID\",\"detail\":\"管理密钥缺失或无效\"}");
    }

    private static void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                + "\"code\":\"ADMIN_API_FORBIDDEN\",\"detail\":\"开放管理面需机器密钥或 SYSTEM_ADMIN 会话\"}");
    }

    static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }
}
