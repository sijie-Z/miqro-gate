package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.crypto.ConsumerJwtVerifier;
import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authentication for the external-system channel (ADR-0010/0011), protecting
 * {@code /api/v1/billing/**}. A valid SYSTEM_ADMIN session also passes (#724);
 * otherwise the presented credential is either an API key (SHA-256 digest match
 * against an ACTIVE consumer) or an RS256 JWT (verified against the consumer's
 * configured public key, {@code sub} = consumer name). The consumer identity is
 * exposed as a request attribute for the billing controllers.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated consumer id. */
    public static final String CONSUMER_ATTR = "apiConsumerId";
    /** Request attribute holding the consumer's tenant id. */
    public static final String TENANT_ATTR = "apiConsumerTenantId";

    /** Path prefix of the external-system channel. */
    public static final String BILLING_PATH = "/api/v1/billing";
    private static final String KEY_PREFIX = "mqk_api_";

    private final ApiConsumerRepository consumerRepository;
    private final ConsumerJwtVerifier jwtVerifier;
    private final UserContext userContext;

    public ApiKeyAuthFilter(ApiConsumerRepository consumerRepository, ConsumerJwtVerifier jwtVerifier,
            UserContext userContext) {
        this.consumerRepository = consumerRepository;
        this.jwtVerifier = jwtVerifier;
        this.userContext = userContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPaths.lookupPath(request);
        if (!path.startsWith(BILLING_PATH)) {
            chain.doFilter(request, response);
            return;
        }
        // Portal sessions pass through only as SYSTEM_ADMIN (#724): the billing
        // channel is admin-session OR consumer credential — never "any logged-in
        // user", which would leak tenant-wide billing data to a plain USER.
        if (userContext.isAuthenticated() && userContext.getUser().role() == UserRole.SYSTEM_ADMIN) {
            chain.doFilter(request, response);
            return;
        }
        Credential credential = extractCredential(request);
        if (credential != null) {
            ApiConsumer consumer = null;
            // The X-API-Key header is API-key-only; Authorization: Bearer splits
            // by prefix (mqk_api_… = API key, otherwise an RS256 JWT).
            if (credential.origin() == Origin.API_KEY_HEADER) {
                consumer = authenticateApiKey(credential.value());
            } else if (credential.value().startsWith(KEY_PREFIX)) {
                consumer = authenticateApiKey(credential.value());
            } else {
                consumer = authenticateJwt(credential.value());
            }
            if (consumer != null) {
                // Issue #316 channel scope: the billing channel requires
                // billing:read (null scope = full access). Fail closed.
                if (!consumer.allows("billing:read")) {
                    forbidden(request, response);
                    return;
                }
                request.setAttribute(CONSUMER_ATTR, consumer.id());
                request.setAttribute(TENANT_ATTR, consumer.tenantId());
                chain.doFilter(request, response);
                return;
            }
        }
        // #724: an authenticated non-admin without a usable consumer credential
        // is forbidden (not unauthenticated) — and never falls back to the
        // session's tenant.
        if (userContext.isAuthenticated()) {
            forbidden(request, response);
            return;
        }
        unauthorized(request, response);
    }

    private ApiConsumer authenticateApiKey(String key) {
        return consumerRepository.findByKeyDigest(sha256(key)).orElse(null);
    }

    private ApiConsumer authenticateJwt(String token) {
        String subject = ConsumerJwtVerifier.extractSubject(token);
        if (subject == null) {
            return null;
        }
        ApiConsumer consumer = consumerRepository.findByName(subject).orElse(null);
        if (consumer == null || !"ACTIVE".equals(consumer.status()) || !consumer.hasJwtKey()) {
            return null;
        }
        if (!jwtVerifier.verify(token, consumer.jwtPublicKeyPem(), subject)) {
            return null;
        }
        return consumer;
    }

    /** Where the presented credential came from. */
    private enum Origin {
        API_KEY_HEADER, BEARER
    }

    private record Credential(String value, Origin origin) {
    }

    private static Credential extractCredential(HttpServletRequest request) {
        String apiKeyHeader = request.getHeader("X-API-Key");
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return new Credential(apiKeyHeader.trim(), Origin.API_KEY_HEADER);
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return new Credential(auth.substring("Bearer ".length()).trim(), Origin.BEARER);
        }
        return null;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeProblem(request, response, HttpServletResponse.SC_FORBIDDEN, "Scope denied", "CONSUMER_SCOPE_DENIED",
                "该消费者未被授予 billing:read 能力。");
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeProblem(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required", "UNAUTHORIZED",
                "A valid API key or portal session is required.");
    }

    /**
     * Contract §2 requires every error response — filter rejections included — to
     * carry a unique {@code requestId}. Billing consumers have no session cookie
     * to fall back on, so this token is their only correlation handle.
     */
    private static void writeProblem(HttpServletRequest request, HttpServletResponse response, int status,
            String title, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String requestId = requestId(request);
        response.getWriter().write(String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\",\"requestId\":\"%s\"}",
                title, status, code, detail, requestId));
    }

    static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        String value = header != null && !header.isBlank() ? header : java.util.UUID.randomUUID().toString();
        // #445: the header is client-controlled and must not break out of the JSON string.
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
