package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.AdminApiKey;
import com.miqroera.miqrokey.domain.model.AdminApiKeyCapabilities;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.controlplane.service.AuditSummaries;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * with {@code 403 ADMIN_API_SCOPE_DENIED} — the answer does not depend on the
 * audit write succeeding (#1408).
 * </p>
 */
public class AdminApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AdminApiKeyAuthFilter.class);

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
        String path = RequestPaths.lookupPath(request);
        if (!path.startsWith(OPEN_PATH)) {
            chain.doFilter(request, response);
            return;
        }
        if (userContext.isAuthenticated()) {
            // Portal sessions need SYSTEM_ADMIN on the open surface (parity with
            // /api/v1/admin/**); their tenant seeds the same attribute contract.
            if (userContext.getUser().role() != UserRole.SYSTEM_ADMIN) {
                forbidden(request, response);
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
                    forbiddenScope(request, response);
                    return;
                }
                chain.doFilter(request, response);
                return;
            }
        }
        unauthorized(request, response);
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
        String required = capabilityFor(RequestPaths.lookupPath(request));
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

    /**
     * Records the scope denial, but never at the cost of answering it.
     *
     * <p>
     * #1408: the audit write is best-effort. Any failure inside
     * {@link AuditService#record} used to propagate out of this filter — which runs
     * outside any transaction and before {@code forbiddenScope} — so the caller
     * received a 500 for a request the gateway had already decided to deny. The
     * denial is the security decision and must not be coupled to the availability
     * of the audit table; the loss of the row is traded for a loud log line
     * instead. Answering 500 also hid the denial from alerting that keys on the
     * documented {@code ADMIN_API_SCOPE_DENIED} contract.
     * </p>
     */
    private void auditDenied(HttpServletRequest request) {
        Object issuer = request.getAttribute(ISSUER_ATTR);
        Object tenantId = request.getAttribute(TENANT_ATTR);
        Object keyId = request.getAttribute(KEY_ATTR);
        if (!(issuer instanceof UUID) || !(tenantId instanceof UUID) || !(keyId instanceof UUID)) {
            return;
        }
        // #1382: the path is client-controlled and arrives DECODED, so a
        // percent-encoded control character reaches this point as a raw byte.
        // Splicing it into the summary by hand produced a non-JSON document that
        // the ::jsonb round-trip in AuditServiceImpl rejected — no audit row, and
        // the throw escaped this filter before forbiddenScope() could answer.
        // AuditSummaries serializes the value instead of splicing it.
        try {
            auditService.record((UUID) tenantId, (UUID) issuer, "ADMIN_API_KEY_SCOPE_DENIED", "ADMIN_API_KEY",
                    (UUID) keyId, AuditSummaries.summary("path", RequestPaths.lookupPath(request)), null);
        } catch (RuntimeException e) {
            // The row is gone, so this line is the only surviving trace of the
            // denial — it must stay truthful and unforgeable, hence forLog.
            LOG.error("Scope denial could not be audited (keyId={}, tenantId={}, path={}); answering 403 anyway", keyId,
                    tenantId, forLog(RequestPaths.lookupPath(request)), e);
        }
    }

    /**
     * Flattens control, line-separator and paragraph-separator characters so a
     * crafted path cannot forge extra log lines (it is decoded before it reaches
     * this sink, so it is caller-controlled text). Mirrors
     * {@code ApiKeyAuthFilter.forLog}; this line is the only remaining trace of a
     * denial whose audit row was lost, so it must not be forgeable.
     */
    private static String forLog(String value) {
        return value == null ? "?" : value.replaceAll("[\\p{C}\\p{Zl}\\p{Zp}]", "?");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Contract §2 requires every error response — filter rejections included — to
     * carry a unique {@code requestId} so a machine caller can correlate the
     * failure with the server-side log line. The envelope is serialized by
     * {@link ProblemJson} rather than spliced, because it carries the echoed
     * client-controlled {@code X-Request-Id}.
     */
    private static void writeProblem(HttpServletResponse response, HttpServletRequest request, int status, String title,
            String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ProblemJson.of(status, title, code, detail, requestId(request)));
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeProblem(response, request, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "ADMIN_API_KEY_INVALID",
                "管理密钥缺失或无效");
    }

    private static void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeProblem(response, request, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "ADMIN_API_FORBIDDEN",
                "开放管理面需机器密钥或 SYSTEM_ADMIN 会话");
    }

    private static void forbiddenScope(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeProblem(response, request, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "ADMIN_API_SCOPE_DENIED",
                "该管理密钥的能力组不含此端点所需权限");
    }

    static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        // #445: this header is client-controlled. It reaches the response only through
        // ProblemJson.of, which serializes the envelope instead of splicing it.
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }
}
