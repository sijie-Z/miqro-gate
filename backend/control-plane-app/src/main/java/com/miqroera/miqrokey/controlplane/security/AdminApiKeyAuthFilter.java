package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.model.AdminApiKeyCapabilities;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * Authentication for the open admin surface (ADR-0015), protecting
 * {@code /api/v1/admin-api/**}. A SYSTEM_ADMIN portal session also passes and
 * is treated like a machine key of its own tenant; otherwise the presented
 * {@code Authorization: Bearer mqk_admin_…} credential is matched against the
 * stored SHA-256 digest (revoked keys fail immediately, expiry is checked). The
 * key identity is exposed as request attributes for the open controllers.
 *
 * <p>
 * F60 batch 3 scope enforcement: a key with a capability scope may only reach
 * paths mapped to its capabilities; {@code null} scope keeps full access.
 * Denied attempts are audited (when the key has an issuing admin) and answered
 * with {@code 403 ADMIN_API_SCOPE_DENIED}.
 * </p>
 */
public class AdminApiKeyAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated admin key id. */
    public static final String KEY_ATTR = "adminApiKeyId";
    /** Request attribute holding the key's tenant id. */
    public static final String TENANT_ATTR = "adminApiKeyTenantId";
    /** Request attribute holding the key name. */
    public static final String NAME_ATTR = "adminApiKeyName";
    /**
     * Request attribute holding the executor/delegator user id: for machine keys
     * the issuing admin (ADR-0016 option A delegation), for SYSTEM_ADMIN sessions
     * the session user. Write ops record it as actor/created_by.
     */
    public static final String ISSUER_ATTR = "adminApiKeyIssuerId";

    /** Path prefix of the open admin surface. */
    public static final String OPEN_PATH = "/api/v1/admin-api";
    private static final String KEY_PREFIX = "mqk_admin_";

    private final AdminApiKeyRepository repository;
    private final UserContext userContext;
    private final AuditService auditService;

    public AdminApiKeyAuthFilter(AdminApiKeyRepository repository, UserContext userContext, AuditService auditService) {
        this.repository = repository;
        this.userContext = userContext;
        this.auditService = auditService;
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
            request.setAttribute(ISSUER_ATTR, userContext.getUser().id());
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7) {
            String token = authorization.substring(7).trim();
            if (token.startsWith(KEY_PREFIX) && authenticate(token, request)) {
                if (!scopeAllows(request)) {
                    auditDenied(request);
                    forbiddenScope(response);
                    return;
                }
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
        request.setAttribute("adminApiKeyCapabilities", key.capabilities());
        if (key.createdBy() != null) {
            request.setAttribute(ISSUER_ATTR, key.createdBy());
        }
        return true;
    }

    /**
     * F60 batch 3 enforcement: unscoped keys (null capabilities) keep full access;
     * scoped keys must hold the capability mapped to the request path.
     */
    @SuppressWarnings("unchecked")
    private boolean scopeAllows(HttpServletRequest request) {
        List<String> capabilities = (List<String>) request.getAttribute("adminApiKeyCapabilities");
        if (capabilities == null) {
            return true;
        }
        String required = capabilityFor(request.getRequestURI());
        return required != null && capabilities.contains(required);
    }

    /** Maps an open-surface path to the capability group that guards it. */
    private static String capabilityFor(String path) {
        String rest = path.startsWith(OPEN_PATH) ? path.substring(OPEN_PATH.length()) : path;
        if (rest.startsWith("/usage/") || rest.startsWith("/audit-events") || rest.startsWith("/api-keys")
                || rest.startsWith("/quota-rules") || rest.startsWith("/mcp-access-logs")) {
            return AdminApiKeyCapabilities.USAGE_READ;
        }
        if (rest.startsWith("/alert-rules") || rest.startsWith("/webhooks")) {
            return AdminApiKeyCapabilities.ALERTS_WRITE;
        }
        if (rest.startsWith("/export-tasks")) {
            return AdminApiKeyCapabilities.EXPORTS_CREATE;
        }
        if (rest.startsWith("/virtual-keys")) {
            return AdminApiKeyCapabilities.VKEYS_DELEGATE;
        }
        return null;
    }

    private void auditDenied(HttpServletRequest request) {
        Object issuer = request.getAttribute(ISSUER_ATTR);
        Object tenantId = request.getAttribute(TENANT_ATTR);
        Object keyId = request.getAttribute(KEY_ATTR);
        if (!(issuer instanceof UUID) || !(tenantId instanceof UUID) || !(keyId instanceof UUID)) {
            return;
        }
        auditService.record((UUID) tenantId, (UUID) issuer, "ADMIN_API_KEY_SCOPE_DENIED", "ADMIN_API_KEY", (UUID) keyId,
                "{\"path\":\"" + safeJson(request.getRequestURI()) + "\"}", null);
    }

    private static String safeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private static void forbiddenScope(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                + "\"code\":\"ADMIN_API_SCOPE_DENIED\",\"detail\":\"该管理密钥的能力组不含此端点所需权限\"}");
    }

    static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }
}
